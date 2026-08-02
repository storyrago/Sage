# 토큰 무효화 설계

- 상태: 확정
- 선행: `2026-08-01-subscription-revocation-design.md` §6, `2026-08-01-rest-authorization-design.md`(응답 코드 판단), `2026-08-02-account-deletion-design.md` §6
- 범위: 백엔드 + 프론트 로그아웃 호출. 스키마 변경 없음

## 1. 배경과 목표

`JwtAuthenticationFilter`는 서명과 만료만 본다. DB도 캐시도 조회하지 않는다. 그래서 **한 번 발급된 토큰은 만료(현재 1시간)까지 무조건 유효하다.**

두 가지가 여기서 나온다.

**계정을 지워도 그 사람은 계속 인증을 통과한다.** 회원 행은 사라졌는데 토큰은 살아 있으므로, 그 요청이 어디까지 가는지는 각 엔드포인트의 인가가 답하게 된다. REST 인가 설계에서 "삭제된 회원의 메시지 목록 조회가 404냐 403이냐"를 논쟁한 것이 이 때문이다 — **인증이 답했어야 할 질문을 인가가 대신 답하고 있었다.**

**로그아웃이 서버에 존재하지 않는다.** 프론트가 `localStorage`를 지우는 것이 전부다(`App.tsx:108`). 공용 PC에서 로그아웃해도 그 토큰은 만료까지 유효하다.

**목표:** 계정 삭제와 로그아웃이 토큰을 즉시 무효화하게 한다.

## 2. 결정

**D1. 거부목록을 Redis에 두고 TTL을 토큰 수명에 맞춘다.**

| 키 | 값 | TTL | 쓰는 곳 |
|---|---|---|---|
| `jwt:denylist:jti:{jti}` | `"1"` | 그 토큰의 남은 수명 | 로그아웃 |
| `jwt:denylist:member:{memberId}` | 무효화 시각(epoch millis) | 액세스 토큰 최대 수명 | 계정 삭제, `jti` 없는 토큰의 로그아웃 |

TTL이 지나면 키가 스스로 사라진다. 정리 작업이 필요 없다.

**회원 단위 키는 플래그가 아니라 시각이다.** 플래그로 두면 "이 회원의 모든 토큰 거부"가 되어 **재로그인까지 막힌다.** 시각으로 두고 토큰의 발급 시각(`iat`)이 그보다 이전일 때만 거부하면, 재로그인으로 받은 토큰은 발급 시각이 더 나중이라 통과한다.

**D2. 토큰에 `jti`를 추가한다.**

`JwtTokenProvider.createAccessToken`이 `UUID` 기반 `jti`를 싣는다. 이미 발급된 토큰에는 `jti`가 없다 — 그 토큰으로 로그아웃하면 **회원 단위 무효화로 대체한다.**

대가는 그 회원의 다른 기기도 함께 로그아웃되는 것이다. 로그아웃이 조용히 실패하는 것보다 낫고, 이 상황은 배포 후 최대 1시간(액세스 토큰 수명) 동안 `jti` 없는 토큰을 들고 있는 사용자에게만 발생한다.

**D3. 로그인 성공 시 그 회원의 회원 단위 키를 지운다.**

`iat`는 초 단위라, 같은 초에 로그아웃하고 재로그인하면 새 토큰이 거부될 수 있다. 로그인 시 키를 지우면 이 경계가 사라진다. 삭제된 계정은 애초에 로그인할 수 없으므로 계정 삭제 쪽 보호가 약해지지 않는다.

토큰을 발급하는 경로는 둘이다 — `AuthService.login`(이메일·비밀번호)과 `OAuth2SuccessHandler`(소셜). **두 곳 모두** 키를 지워야 한다. 한쪽만 하면 그 경로로 재로그인한 사용자가 같은 경계에 걸린다.

**D4. 판정은 `TokenDenylist` 한 곳에서 한다.**

`RoomAccess`(멤버십)·`ImageReferences`(이미지 참조)와 같은 자리다.

```java
boolean isRevoked(String jti, Long memberId, Long issuedAtMillis);
void revokeToken(String jti, Long expiresAtMillis);
void revokeMember(Long memberId);
void clearMember(Long memberId);
```

시각은 전부 epoch millis다. `JwtTokenProvider.getExpiresAt`가 이미 그 형태로 돌려준다(파싱 실패 시 `null`).

**D5. 검사는 토큰을 검증하는 두 지점에 붙인다.**

REST는 `JwtAuthenticationFilter`, WebSocket은 `JwtAuthChannelInterceptor`의 CONNECT 분기다. 한쪽만 막으면 다른 쪽이 열린 채 남는다.

**D6. `POST /api/auth/logout`을 만든다.**

인증된 요청의 토큰에서 `jti`와 만료를 읽어 거부목록에 올리고 204를 돌려준다. `jti`가 없으면 회원 단위 무효화로 대체한다(D2). 프론트는 `localStorage`를 지우기 **전에** 호출한다.

**D7. Redis 조회가 실패하면 통과시킨다(fail-open).**

거부(fail-closed)가 보안상 옳지만, Redis가 죽으면 전 사용자가 로그인 불가가 된다. 이 앱에서 Redis 장애는 이미 프레즌스를 깨뜨리고, `ChatMessageController`가 STOMP로 받은 메시지를 배달하는 유일한 경로(`redisPublisher.publish`)도 끊는다 — 메시지는 DB에 저장되지만 아무에게도 배달되지 않는다. 인증까지 막으면 서비스가 통째로 멈춘다. 통과시키고 경고를 남긴다.

*전제*: 이 통과가 빠르려면 `spring.data.redis.timeout`·`connect-timeout`이 설정돼 있어야 한다. 미설정 시 Lettuce 기본 타임아웃(60초)이 그대로 걸려, "빠르게 통과"가 아니라 요청마다 워커 스레드가 60초 멎는다. 실측(`TokenDenylist.isRevoked` 1건): 정상 응답 2~3ms, 연결 거부(포트 닫힘) 6~7ms — 무해, 무응답(TCP는 붙는데 응답 없음) 60,114ms. 두 타임아웃을 500ms로 설정한 뒤 무응답 상황은 620ms로 줄어든다.

*대가*: Redis 장애 중에는 무효화가 적용되지 않는다. 배포 후 로그를 확인해야 한다.

## 3. 구조

| 파일 | 변경 |
|---|---|
| `security/TokenDenylist.java` (신규) | 무효화 판단·등록의 유일한 지점 |
| `security/JwtTokenProvider.java` (수정) | `jti` 발급, `jti`·발급 시각 조회 |
| `security/JwtAuthenticationFilter.java` (수정) | 검증 후 거부목록 확인 |
| `security/JwtAuthChannelInterceptor.java` (수정) | CONNECT에 동일 검사 |
| `controller/AuthController.java` (수정) | `POST /api/auth/logout` |
| `service/AuthService.java` (수정) | 로그인 성공 시 회원 단위 키 삭제(D3) |
| `security/OAuth2SuccessHandler.java` (수정) | 소셜 로그인도 같은 삭제를 한다 |
| `event/TokenRevocationListener.java` (신규) | `MemberDeletedEvent`를 받아 `revokeMember` |
| `frontend/src/lib/api.ts`·`App.tsx` (수정) | 로그아웃 호출 |

새 이벤트 타입을 만들지 않는다. `MemberDeletedEvent`는 이미 존재하고 구독 회수가 쓰고 있다.

`TokenDenylist`는 `StringRedisTemplate`을 쓴다 — 값이 문자열이고, 기존 `PresenceRegistry`가 같은 것을 쓴다.

## 4. 데이터 흐름

```
로그아웃
  POST /api/auth/logout (Authorization: Bearer …)
    ├ jti가 있으면 → jwt:denylist:jti:{jti} = "1", TTL = 만료까지
    └ jti가 없으면 → jwt:denylist:member:{id} = now, TTL = 액세스 토큰 수명
  → 204 → 프론트가 localStorage 삭제

계정 삭제
  DELETE /api/members/me → 커밋
    └ MemberDeletedEvent (AFTER_COMMIT)
        ├ 구독 회수 (기존)
        └ revokeMember(id) → jwt:denylist:member:{id} = now

이후 모든 요청·CONNECT
  서명·만료 검증 → TokenDenylist.isRevoked(jti, memberId, iat)
    ├ jti가 거부목록에 있으면 → 인증 실패
    ├ iat < 회원 단위 무효화 시각이면 → 인증 실패
    └ Redis 조회 실패 → 통과, 경고 로그
```

## 5. 오류 처리

- **Redis 조회 실패** → 통과시키고 경고를 남긴다(D7).
- **Redis 쓰기 실패(로그아웃)** → 로그아웃 요청을 실패로 응답한다. 사용자는 "로그아웃됐다"고 믿으면 안 된다. 계정 삭제 쪽 무효화 실패는 삼키고 경고를 남긴다 — 삭제 자체는 이미 커밋됐고 되돌릴 수 없다.
- **`jti`가 없는 토큰** → 회원 단위 무효화로 대체한다(D2).
- **인증 실패 응답** → 기존 `UNAUTHORIZED`(401)를 그대로 쓴다. 새 `ErrorCode`를 만들지 않는다.
- **거부된 토큰의 WS CONNECT** → 기존 인증 실패 경로와 같다. 사용자가 없는 상태로 CONNECT가 규칙에 걸려 세션이 닫힌다.

## 6. 남기는 한계

- **이미 발급된 `jti` 없는 토큰은 개별 로그아웃이 안 된다.** 회원 단위 무효화로 대체하므로 그 회원의 다른 기기도 함께 로그아웃된다. 배포 후 최대 1시간.
- **살아 있는 WebSocket 세션은 끊기지 않는다.** 무효화는 CONNECT만 막는다. 정상 클라이언트는 로그아웃 시 스스로 소켓을 닫지만, 붙잡고 있는 클라이언트는 계속 연결돼 있을 수 있다. **탈퇴와 로그아웃은 여기서 갈린다.** 탈퇴는 멤버십 행이 사라지므로(`chatRoomMemberRepository.deleteByMember`) `/pub/**` 전송이 거부되고 구독도 회수돼 안전하지만, 로그아웃은 멤버십을 건드리지 않는다.
  이 상한(최대 1시간)은 **송신에만** 적용된다. `JwtAuthChannelInterceptor`가 CONNECT 시 세션에 저장한 `tokenExpiresAt`으로 이후 인바운드 프레임(`/pub/**`로 가는 SEND, 타이핑 이벤트 등 클라이언트→서버)만 막기 때문이다. **수신**(그 방 메시지가 서버→클라이언트로 오는 것)과 **프레즌스 온라인 상태**는 이 검사를 타지 않는다 — 프레즌스는 평소엔 `SessionUnsubscribeEvent`(`WebSocketEventListener.onUnsubscribe`)로도 정리되지만, 만료 이후에는 UNSUBSCRIBE도 이 인바운드 검사에 걸려 막히므로 결국 소켓이 실제로 닫힐 때(`SessionDisconnectEvent`)까지 정리되지 않는다. 그래서 소켓을 붙잡은 클라이언트는 시간 제한 없이 그 방 메시지를 계속 받고 온라인으로 남을 수 있다. 그 사람은 원래 그 방의 멤버였으므로 권한 상승은 아니다. 세션을 강제로 끊으려면 열려 있는 `WebSocketSession`을 보관하는 레지스트리가 필요하고, 그것은 구독 회수 설계가 의도적으로 피한 방향이다. **강제 로그아웃이나 계정 탈취 대응 기능을 만들 때 함께 만든다.**
- **회원 단위 로그아웃은 다음 로그인까지만 유효하다.** D3은 `iat` 초 단위 경계 때문에 로그인 시 회원 단위 키를 지운다(`clearMember`). 그 대가를 "삭제된 계정은 애초에 로그인할 수 없다"로 정당화했는데, 이는 계정 삭제에만 맞고 로그아웃에는 틀린다: `jti` 없는 구 토큰으로 폰에서 로그아웃 → 노트북의 구 토큰도 함께 죽는다(의도한 대가) → 폰에서 곧바로 재로그인 → `clearMember` → **노트북의 구 토큰이 다시 살아난다.** 지금 범위(배포 후 최대 1시간, `jti` 없는 토큰에 한함)에서는 감당 가능하지만, 강제 로그아웃·계정 탈취 대응·비밀번호 변경 시 전체 세션 무효화(모두 위에서 별도 작업으로 미뤘다)는 전부 회원 단위 키를 쓸 것이고, 그때는 "공격자가 훔친 비밀번호로 재로그인하면 방금 건 전체 무효화가 해제된다"가 된다. 그 기능들을 만들 때는 키를 지우는 대신 **발급 시각 정밀도를 올리거나 무효화 시각을 뒤로 미루는 방식**으로 풀어야 한다.
- **거부목록을 붙인 볼륨의 롤백은 커밋 revert만으로 안 된다.** compose에서 `volumes:`/`command:`를 지우고 `up -d` 해도 **명명 볼륨이 그대로 다시 마운트된다**(compose가 구 컨테이너의 마운트를 새 컨테이너로 이어 붙인다) — `appendonly`만 꺼진 채 `/data`에 죽은 `appendonlydir`가 남고 RDB로 도는 어정쩡한 상태가 된다. 진짜로 떼려면 `docker compose down` 후 `up -d`가 필요하고, 그때 명명 볼륨은 삭제되지 않고 **고아로 남는다.** 반대 방향으로 **`docker compose down -v`는 거부목록을 통째로 날린다** — 볼륨을 붙이기 전에는 `down -v`가 무해했다.
- **Redis 장애 중에는 무효화가 적용되지 않는다**(D7의 대가). 이 경우는 조회 실패라 경고 로그가 남는다. `maxmemory-policy`를 `allkeys-lru` 류로 바꾸면 사정이 다르다 — 거부목록 키가 evict된 상태는 "키가 원래 없음"과 구분되지 않으므로, `isRevoked`는 실패가 아니라 정상 경로로 `false`를 돌려준다. **무효화가 경고 로그 한 줄 없이 조용히 뚫린다.** 볼륨과 AOF는 재시작·재생성에서 데이터를 지켜주지만 이 문제는 막지 못한다 — 기본값(`noeviction`)을 유지해야 한다.
- **Redis가 무응답이면 앱이 부팅하지 못한다.** `RedisConfig.messageListenerContainer`가 라이프사이클 시작 시점에 동기로 연결을 시도하고, 실패하면 컨텍스트 기동 자체가 실패한다. `spring.data.redis.timeout`·`connect-timeout`(500ms)을 걸어도 이 실패는 남는다 — 걸리는 시간이 분 단위에서 초 단위로 줄어들 뿐이다. Redis 없이는 `redisPublisher.publish`로 도는 메시지 팬아웃이 죽으므로, 리스너를 지연 기동으로 바꿔 부팅은 통과시키는 방향은 택하지 않고 실패를 그대로 남긴다.
- **정석은 짧은 액세스 토큰 + 리프레시 토큰이다.** 그러면 무효화 판정이 리프레시 한 지점으로 모이고 거부목록이 거의 필요 없어진다. 프론트 재발급 흐름·저장 위치·엔드포인트가 함께 붙어 이번 범위를 넘는다. 이 설계는 그 방향을 막지 않는다.
- **비밀번호 변경 시 전체 세션 무효화는 다루지 않는다.** 이 앱에 비밀번호 변경 기능 자체가 없다.

## 7. 검증

**자동 테스트 — "통과하지 못한다"를 본다.**

- 로그아웃한 토큰으로 API를 호출하면 401이다
- **같은 회원의 다른 기기 토큰은 계속 유효하다**(`jti` 단위 무효화)
- `jti`가 없는 토큰으로 로그아웃하면 그 회원의 토큰 전부가 거부된다
- 회원 단위 무효화 이후 **재로그인해서 받은 토큰은 통과한다**(시각 비교)
- 계정 삭제 후 그 토큰으로 API를 호출하면 401이다
- 계정 삭제 후 그 토큰으로 WS CONNECT를 시도하면 거부된다
- 거부목록 TTL이 토큰 만료 시각에 맞춰 설정된다
- Redis 조회가 실패하면 통과하고 경고를 남긴다
- 로그인 성공이 그 회원의 회원 단위 키를 지운다

**프론트 검증**: `npm run lint && npm test && npm run build`.

**배포 후 실측**

- 로그아웃한 뒤 옛 토큰으로 API를 호출하면 401인지(개발자 도구에서 토큰을 복사해 확인)
- 로그아웃해도 다른 기기의 세션이 유지되는지
- 탈퇴 직후 그 토큰으로 접속이 막히는지
- 로그아웃 → 재로그인이 곧바로 되는지
