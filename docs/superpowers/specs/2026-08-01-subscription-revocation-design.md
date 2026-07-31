# 멤버십 취소 시 구독 회수 설계

- 상태: 확정
- 선행: `2026-07-31-room-authorization-design.md` §4 D4, PR 1(WS 인가) 머지 완료
- 범위: 백엔드 + 프론트 오류 코드 분기 2줄. 나가기·탈퇴 UI는 범위 밖(현재 프론트에 호출이 없다)

## 1. 배경과 목표

WS 인가는 `SUBSCRIBE` 시점에 한 번만 평가된다. 브로커가 구독 테이블에 항목을 적고 나면 그 뒤로는 아무도 멤버십을 다시 묻지 않는다.

방을 나가면 `chatroom_members` 행은 지워지지만 브로커의 구독 항목은 남는다. 소켓이 살아 있는 동안 그 방의 모든 메시지가 계속 그 세션으로 전달된다. 화면에 그리지 않을 뿐 네트워크로는 도착한다.

**목표:** 멤버십이 사라지는 순간, 그 회원의 살아 있는 세션에서 해당 방 구독을 서버가 회수한다.

## 2. 결정

**R1. 세션을 닫지 않고 구독만 회수한다.**

선행 설계 §4 D4는 세션 전체 종료를 택하면서 "그 사용자의 다른 방 구독도 함께 끊긴다"를 대가로 인정했다. 이 대가는 치를 필요가 없다. `SimpUserRegistry`가 사용자 → 세션 → 구독(id·destination)을 이미 보유하므로 회수 대상의 `subscriptionId`를 특정할 수 있다.

서버가 그 세션 명의의 `UNSUBSCRIBE` 프레임을 만들어 `clientInboundChannel`에 넣으면 브로커가 구독 테이블에서 그 항목만 지운다. 소켓과 다른 방 구독은 유지된다.

**R2. 회수 후 `SessionUnsubscribeEvent`를 직접 발행한다.**

`SessionUnsubscribeEvent`는 `StompSubProtocolHandler#handleMessageFromClient`에서만 발행된다(spring-websocket 7.0.6 기준, 소스 전체에서 유일한 발행 지점). 채널에 직접 넣은 프레임은 그 경로를 지나지 않으므로 이벤트가 없다.

이 저장소는 그 이벤트로 방 프레즌스를 정리한다(`WebSocketEventListener#onUnsubscribe` → `PresenceRegistry#leaveBySubscription`). 발행하지 않으면 회수된 회원이 접속자 목록에 남아 같은 방의 다른 사람 화면에 계속 접속 중으로 보인다. `DefaultSimpUserRegistry`의 구독 목록도 같은 이유로 갱신되지 않는다.

따라서 회수는 **프레임 전송 + 같은 프레임으로 `SessionUnsubscribeEvent` 발행**이 한 쌍이다. 기존 소비자 두 곳이 그대로 동작한다.

**R3. 회수 대상은 방의 구독 3종이고, 목적지는 완전 일치로 판정한다.**

`/sub/chatrooms/{id}`, `/sub/chatrooms/{id}/typing`, `/sub/chatrooms/{id}/presence`. 셋 다 멤버십으로 인가된 목적지이므로 하나만 남겨도 구멍이다.

**접두사 일치를 쓰지 않는다.** `startsWith("/sub/chatrooms/3")`은 방 30·31·300의 구독까지 회수한다. 세 문자열과의 `equals`로 판정한다. `revokeAll`은 `/sub/chatrooms/` 접두사에 더해 다음 세그먼트가 숫자임을 요구한다.

**R4. 트리거는 두 개다.**

| 트리거 | 회수 범위 |
|---|---|
| 방 나가기 (`ChatRoomMemberService#leave`) | 그 방의 구독 3종 |
| 회원 탈퇴 (`MemberService#delete`) | 그 회원의 모든 방 구독 |

개인 큐 구독(`/user/queue/unread`, `/user/queue/errors`)은 회수하지 않는다. 지우면 R6의 통지가 도달하지 못한다.

강퇴는 방장 개념이 없어 기능 자체가 없다(선행 설계 §9 한계 2). 범위 밖이다.

**R5. 회수는 커밋 이후에 한다.**

`@TransactionalEventListener(AFTER_COMMIT)`로 처리한다. 트랜잭션이 롤백되면 멤버십이 그대로이므로 구독을 지우면 안 된다. `leave`·`delete` 모두 `@Transactional`이 붙어 있어 커밋 경계가 존재한다.

**R6. 회수 사유는 인가 거부와 다른 코드로 통지한다.**

구독만 지우면 그 방을 보고 있던 다른 탭은 아무 안내 없이 조용해진다. 회수한 **(세션, 방) 조합마다 1건**을 `/user/queue/errors`로 보낸다. `destination`은 그 방의 채팅 목적지(`/sub/chatrooms/{id}`)다.

코드는 `NOT_JOINED_ROOM`을 재사용하지 않고 `ROOM_MEMBERSHIP_REVOKED`를 새로 만든다. 재사용하면 **나가기를 누른 본인 탭에도 "참여하지 않은 채팅방입니다." 오류 토스트가 뜬다** — 성공한 정상 동작이 실패로 표시된다. 개인 목적지 전송은 그 회원의 모든 세션에 배달되므로 이 문제를 백엔드만으로는 피할 수 없다(HTTP 요청에는 WS `sessionId`가 없어 개시 세션을 제외할 방법이 없다).

프론트는 `onAuthzError`에서 이 코드일 때 토스트를 띄우지 않고 랜딩 복귀만 한다. **프론트 변경은 이 분기 2줄이 전부다.**

## 3. 구조

| 파일 | 책임 |
|---|---|
| `security/RoomSubscriptionRevoker.java` (신규) | 회원의 세션에서 대상 구독을 찾아 `UNSUBSCRIBE` 주입 + 이벤트 발행 + 통지 |
| `event/RoomLeftEvent.java` (신규) | `record RoomLeftEvent(Long memberId, Long roomId)` |
| `event/MemberDeletedEvent.java` (신규) | `record MemberDeletedEvent(Long memberId)` |
| `event/SubscriptionRevocationListener.java` (신규) | 두 이벤트를 `AFTER_COMMIT`에서 받아 revoker 호출 |
| `global/exception/ErrorCode.java` (수정) | `ROOM_MEMBERSHIP_REVOKED` 추가 |
| `service/ChatRoomMemberService.java` (수정) | `leave()`에서 `RoomLeftEvent` 발행 |
| `service/MemberService.java` (수정) | `delete()`에서 `MemberDeletedEvent` 발행 |
| `frontend/src/App.tsx` (수정) | `onAuthzError`에서 회수 코드 분기 |

`RoomSubscriptionRevoker`의 인터페이스:

```java
void revokeRoom(Long memberId, Long roomId);   // 방 나가기
void revokeAll(Long memberId);                 // 회원 탈퇴
```

의존: `SimpUserRegistry`, `MessageChannel`(**`@Qualifier("clientInboundChannel")` 필수** — `MessageChannel` 타입 빈이 셋이라 이름 폴백에 기대면 리팩터링 시 다른 채널로 조용히 바뀐다), `ApplicationEventPublisher`, `SimpMessagingTemplate`.

`SimpUserRegistry`의 사용자 키는 `Authentication#getName`이고 `CustomUserDetails#getUsername`이 `String.valueOf(memberId)`를 돌려준다. `RedisSubscriber`가 개인 큐에 쓰는 키와 같다.

## 4. 데이터 흐름

```
DELETE /api/chatrooms/3/members
  └ leave(): 멤버십 행 삭제, RoomLeftEvent(7, 3) 발행 → 커밋
      └ AFTER_COMMIT 리스너 → revokeRoom(7, 3)
          ├ simpUserRegistry.getUser("7").getSessions()
          ├ 각 세션의 구독 중 destination이 방 3의 3종과 완전 일치하는 것 선별
          ├ 구독마다: UNSUBSCRIBE 프레임 생성 → clientInboundChannel.send()
          │            → 브로커가 구독 테이블에서 제거
          │            → 같은 프레임으로 SessionUnsubscribeEvent 발행
          │               → PresenceRegistry 정리 + 방 로스터 재방송
          │               → DefaultSimpUserRegistry 구독 제거
          └ 세션·방 조합마다 /user/queue/errors 로 통지 1건
```

**주입 프레임에 싣는 헤더는 세 개뿐이다.** `simpMessageType=UNSUBSCRIBE`(`StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE)`로 생성하면 자동 충족), `simpSessionId`, `simpSubscriptionId`.

**세션 속성(`sessionAttributes`)과 `simpUser` 헤더를 복사하지 않는다.** 원본 SUBSCRIBE를 흉내 내려고 세션 속성을 복사하면 `tokenExpiresAt`가 함께 실려, 토큰이 만료된 세션에서는 회수 프레임이 `JwtAuthChannelInterceptor`의 만료 검사에 막힌다 — 회수가 가장 필요한 오래된 세션에서 조용히 실패한다.

주입 프레임은 PR 1의 인터셉터 3개를 지난다. 만료 검사는 세션 속성이 없으므로 통과하고, 인가 규칙은 `UNSUBSCRIBE`를 `permitAll`한다.

## 5. 오류 처리

- **세션이 없다(오프라인).** `getUser`가 `null`이면 아무것도 하지 않는다. 다음 접속의 `SUBSCRIBE`가 규칙에 걸려 거부된다.
- **프레임이 폐기됐다.** `send()`가 `false`를 돌려주면 인터셉터가 프레임을 버린 것이다. 예외가 아니므로 조용하다 — 반환값을 확인해 경고를 남긴다.
- **회수 중 예외.** 리스너는 커밋 이후에 돌므로 예외가 나도 멤버십 삭제는 되돌아가지 않는다. 예외를 삼키고 경고를 남긴다. 회수 실패가 탈퇴 API의 실패로 보이면 안 된다.
- **회수 직후 재구독.** `SUBSCRIBE`는 규칙을 다시 평가하므로 멤버가 아니면 거부된다.
- **회수와 재입장이 겹친다.** 회수는 열거 시점의 `subscriptionId`에만 작용한다. 그 뒤 새 id로 등록된 구독은 건드리지 않는다.
- **동시성.** 같은 회원이 여러 세션을 가질 수 있다. 세션 집합을 순회하며 각각 처리한다.

## 6. 남기는 한계

- **회수는 접근 차단이 아니다.** 방 목록이 전체 공개방이고 방을 고르면 프론트가 자동으로 재가입한다. 회수로 랜딩에 돌아온 사용자가 같은 방을 다시 클릭하면 멤버십이 복구되고 구독도 되살아난다. 진짜 차단은 보류된 비공개방·초대 설계에 달려 있다.
- **삭제된 회원의 소켓과 토큰은 만료까지 살아 있다.** 방 구독은 회수되지만 개인 큐 구독은 유지되고, JWT 서명만 검증하므로 재접속도 성공한다. 계정 삭제 시 토큰 무효화는 별도 논점이다.
- **다중 인스턴스에서 전파되지 않는다.** `SimpUserRegistry`는 그 인스턴스가 들고 있는 세션만 안다. 지금은 앱 컨테이너가 하나다. 확장 시 이미 있는 Redis pub/sub로 회수 요청을 브로드캐스트한다.
- **배달 시점 재검사는 여전히 하지 않는다.** 회수는 취소 시점의 일회성 이벤트다. 회수와 배달 사이의 짧은 창에서 메시지 1건이 지나갈 수 있다. 정석은 선행 설계 §9 한계 1이 남긴 그대로다.
- **클라이언트 상태는 서버가 고치지 않는다.** 브로커 구독만 지우므로 클라이언트는 자기가 구독 중이라고 믿는다. R6의 통지로 드러난다.
- **UI 트리거가 없다.** 프론트에 방 나가기·회원 탈퇴 API 호출이 없다. 두 트리거 모두 현재는 API 직접 호출로만 발생한다.

## 7. 검증

**자동 테스트 — "회수된다"가 아니라 "대상만 회수된다"를 본다.**

- `revokeRoom`이 대상 방의 구독 3종에만 `UNSUBSCRIBE`를 만든다
- **방 3 회수가 방 30·31의 구독을 건드리지 않는다**(완전 일치 판정 고정)
- 대상 회원의 세션만 처리하고 같은 방의 다른 회원 세션은 건드리지 않는다
- `revokeAll`이 그 회원의 모든 방 구독을 회수하고, `/user/queue/*` 개인 큐 구독은 남긴다
- 회수 시 `SessionUnsubscribeEvent`가 발행되어 그 방 접속자 목록에서 빠진다
- 통지가 회수한 (세션, 방)마다 1건 가고 `destination`이 그 방의 채팅 목적지다
- 주입 프레임이 조립된 인터셉터 체인을 통과해 폐기되지 않는다(만료된 세션 포함)
- 세션이 없을 때 예외 없이 통과한다
- 리스너가 `AFTER_COMMIT`에서만 revoker를 부른다(리스너 단위로 검증한다 — `@SpringBootTest @Transactional` 테스트는 커밋 자체가 없어 애너테이션만 있으면 통과한다)

**통합 테스트의 전제.** `SimpUserRegistry`는 `SessionConnectedEvent`·`SessionSubscribeEvent`로만 채워지고 두 이벤트는 실제 소켓 프레임에서만 나온다. 채널에 `SUBSCRIBE`를 넣는 것만으로는 레지스트리가 비어 있어 테스트가 공허하게 통과한다. 두 이벤트를 직접 발행해 레지스트리를 채운 뒤 회수를 호출한다.

**배포 후 실측.** UI가 없으므로 인증 토큰으로 API를 직접 호출해 유발한다.

- 탭 두 개로 같은 방을 열고 `DELETE /api/chatrooms/{id}/members` 호출 → 두 탭이 랜딩으로 돌아가고 본인 탭에 오류 토스트가 뜨지 않는지
- 나간 방의 새 메시지가 더 이상 도착하지 않는지(개발자 도구 WS 프레임)
- 같은 방의 다른 사용자 화면에서 나간 사람이 접속자 목록에서 사라지는지
- 나가지 않은 다른 방 구독과 소켓이 유지되는지
