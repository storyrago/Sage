# CD 파이프라인 — 배포 단위 고정과 기동 검증 설계

- 작성일: 2026-07-29
- 범위: `.github/workflows/cd.yml`, `docker-compose.yml`, `Dockerfile`, `frontend/nginx.conf`, `SecurityConfig`, `build.gradle`

## 1. 배경과 목표

현재 CD는 **어떤 커밋을 배포하는지 보장하지 않고, 배포 결과가 정상인지 확인하지 않는다.**

구조적 결함 세 가지다.

| # | 결함 | 결과 |
|---|---|---|
| 1 | 빌드 잡은 `:latest`와 `:${sha}` 두 태그를 push하는데, 배포 잡은 `docker compose pull`로 **`:latest`만** 받는다 | 마지막에 `:latest`를 쓴 실행이 이긴다. 실행 순서가 뒤집히면 옛 커밋 이미지가 배포된다 |
| 2 | EC2 스크립트가 `git reset --hard origin/develop`으로 compose 파일을 **브랜치 끝**에서 가져온다 | 배포되는 이미지와 compose 설정이 서로 다른 커밋에서 올 수 있다 |
| 3 | `docker compose up -d` 성공 = 잡 성공 | 컨테이너가 기동에 실패해도 CD는 초록으로 끝난다 |

`concurrency: cancel-in-progress`는 **겹치는** 실행만 취소한다. 큐 대기·재실행으로 완료 순서가 뒤집히는 경로는 막지 못한다. 실제로 커밋 `b6d331d`(구버전) 실행이 `0538145` 실행보다 8분 늦게 끝나면서 `:latest`를 덮어썼고, 그 이미지로 컨테이너가 교체됐다. 프론트는 카카오 버튼이 없는 번들로 돌아갔고, 백엔드는 이미 적용된 V5와 코드가 어긋나(엔티티가 V5에서 삭제된 `google_sub`을 매핑, `ddl-auto: validate`) 기동에 실패해 `/api/*`가 502를 반환했다. CD는 성공으로 기록됐다.

목표는 두 가지다.

1. **배포 단위를 커밋 SHA로 고정한다** — 이미지·설정이 항상 한 커밋에서 온다
2. **UP을 확인한 뒤에만 성공으로 처리한다** — 죽은 배포가 초록으로 끝나지 않는다

## 2. 결정 사항

**D1. 배포는 `:latest`를 참조하지 않는다.**
`docker-compose.yml`의 `image:`를 `${IMAGE_TAG:?IMAGE_TAG required}`로 파라미터화하고, 배포 스크립트가 `IMAGE_TAG=$DEPLOY_SHA`를 export한다. 배포 잡은 자기가 방금 빌드한 이미지만 받는다.

`:latest` push는 유지한다. 최신 표식으로 쓸모가 있고, 배포 경로가 더는 참조하지 않으므로 해롭지 않다.

`IMAGE_TAG`가 없으면 `docker compose`가 즉시 실패한다. 폴백으로 가변 태그를 조용히 받는 경로를 없앤 것이다. 배포 스크립트는 매 배포마다 호스트 `.env`에 `IMAGE_TAG=$DEPLOY_SHA`를 임시 파일 경유로 원자적으로 기록해 두므로(§4), EC2에서 태그를 생략하고 `docker compose up -d`를 실행해도 마지막으로 배포된 SHA가 그대로 쓰인다. 특정 버전을 강제하려면 `IMAGE_TAG=<sha> docker compose up -d`로 덮어쓴다.

**D2. compose 파일도 배포 대상 SHA에서 가져온다.**
`git reset --hard origin/develop` → `git reset --hard $DEPLOY_SHA`. EC2의 HEAD는 로컬 브랜치 `develop`에 붙어 있으므로 이 reset은 detach가 아니라 로컬 `develop` 포인터를 배포 대상 SHA로 옮기는 것이다. 다음 배포가 다시 reset하므로 상태는 누적되지 않는다.

이 쪽이 결과적으로 더 낫다: D3 가드에 막혀 배포가 중단된 상태에서 EC2에 로그인해 `git status`를 보면 로컬 `develop`이 `origin/develop`보다 뒤처져 있다고 나와, 밀린 배포가 있다는 사실이 바로 드러난다.

**D3. 배포 대상이 develop 최신이 아니면 중단한다.**
EC2 스크립트가 `git fetch` 직후 `git rev-parse origin/develop`과 `$GITHUB_SHA`를 비교하고, 다르면 사유를 출력하고 종료 코드 1로 끝낸다.

"조상인가"가 아니라 "끝과 같은가"로 검사한다. 사고를 낸 `b6d331d`는 develop의 조상이었으므로 조상 검사로는 걸러지지 않는다.

*대가*: 짧은 간격으로 두 번 푸시하면 뒤처진 실행이 빨간불이 된다. 이는 "이 실행은 배포하지 않았다"는 정직한 신호이므로 그대로 둔다. 일반적인 겹침은 기존 `concurrency`가 먼저 취소한다.

**D4. 기동 검증은 actuator 헬스로 한다.**
`spring-boot-starter-actuator`를 추가하고 `/actuator/health`를 `SecurityConfig`의 permitAll 목록에 넣는다. 현재 `anyRequest().authenticated()`이므로 빠뜨리면 헬스 체크가 401을 받는다.

기본 헬스 인디케이터에 DataSource·Redis가 포함되므로, 이번 같은 스키마 불일치는 기동 실패로 즉시 드러난다.

**D5. 헬스 체크는 컨테이너 `healthcheck`로 정의하고, 배포 스크립트는 `app`·`web` 둘 다의 상태를 기다린다.**
`Dockerfile`(app)과 `frontend/Dockerfile`(web, nginx 스테이지)에 curl을 설치하고(`eclipse-temurin:17-jre`·`nginx:1.27-alpine` 둘 다 없다) compose의 `app`·`web`에 각각 `healthcheck`를 선언한다. 배포 스크립트는 `app`, `web` 순서로 `docker inspect`로 `healthy`를 기다리며, 컨테이너가 아예 없거나 `unhealthy`이거나 타임아웃이면 **해당 서비스 로그 마지막 50줄을 출력하고 잡을 실패시킨다.**

컨테이너에 헬스 상태를 남기는 방식이라 배포 이후에도 `docker compose ps`로 상태를 볼 수 있다. 배포 스크립트에서만 curl을 호출하면 그 순간의 판정만 남는다.

*대기 상한*: 컨테이너 쪽 실질 상한은 app 기준 `start_period 30s` + `interval 10s` × `retries 12` = 150초(web은 `start_period 5s` + `interval 10s` × `retries 6` = 65초로 더 짧다). 배포 스크립트의 서비스당 175초(5초 간격 35회)는 그 위에 여유를 더한 값이다. 문서 안의 대기 시간은 이 기준으로 통일한다.

**D6. `web`의 `depends_on`은 바꾸지 않는다.**
`condition: service_healthy`를 걸면 앱이 죽었을 때 사이트 전체가 뜨지 않는다. 화면은 뜨고 API만 502인 편이 진단에 낫다.

**D7. nginx는 `/actuator`에 404를 반환한다.**
현재 `/actuator` 프록시 규칙이 없어 SPA 폴백이 걸리고, `/actuator/health`가 **200 HTML**을 반환한다. 앱이 죽어 있어도 200이라 오판을 부른다. 헬스를 외부로 열지는 않고, 폴백만 끊는다.

**D8. 배포 후 러너에서 공개 URL로 스모크 테스트한다.**
컨테이너 헬스체크는 컨테이너 내부에서 `localhost`로 검사하므로, DNS·TLS 인증서·80/443 포트 바인딩이 깨져도 healthy로 잡힐 수 있다. SSH 배포 스텝 다음에 별도 스텝으로 `https://sagertc.duckdns.org/`(200), `/api/chatrooms`(302), `/actuator/health`(404)를 curl로 확인하고, 기대와 다르면 잡을 실패시킨다. 사용자가 실제로 들어오는 경로로 직접 확인하는 것이 실서비스 관행이다.

## 3. 변경 파일

| 파일 | 변경 |
|---|---|
| `docker-compose.yml` | `image:` 태그 필수화(D1), `app`·`web` 모두 `healthcheck`(D5) |
| `.github/workflows/cd.yml` | `envs`로 `DEPLOY_SHA` 전달, SHA 검사(D3), SHA 체크아웃(D2), `IMAGE_TAG` export 및 `.env` 원자적 기록(D1), `app`·`web` 헬스 대기·실패 처리(D5), 이미지 정리 정책 변경(§6), 배포 후 스모크 테스트(D8) |
| `Dockerfile` | curl 설치(D5) |
| `frontend/Dockerfile` | curl 설치(D5) |
| `build.gradle` | `spring-boot-starter-actuator`(D4) |
| `SecurityConfig.java` | permitAll에 `/actuator/health`(D4) |
| `frontend/nginx.conf` | `location /actuator/ { return 404; }`(D7) |

## 4. 배포 스크립트 흐름

```
git fetch origin
origin/develop 끝 == $DEPLOY_SHA ?  → 아니면 사유 출력 후 실패        (D3)
git reset --hard $DEPLOY_SHA                                        (D2)
export IMAGE_TAG=$DEPLOY_SHA                                        (D1)
.env에 IMAGE_TAG=$DEPLOY_SHA 원자적 기록 (임시 파일 경유)                (D1)
docker compose pull        # 이 SHA 태그만 받는다. 실패 시 기존 컨테이너 유지
docker compose up -d --remove-orphans
docker image prune -af --filter "until=72h"                         (§6)
app·web 컨테이너가 각각 healthy 될 때까지 대기                          (D5)
  → 컨테이너 없음·unhealthy·타임아웃: docker compose logs --tail=50 <서비스> 후 실패
[별도 스텝] 공개 URL 스모크 테스트: /, /api/chatrooms, /actuator/health  (D8)
```

## 5. 검증

- `./gradlew test` — actuator·Security 변경이 기존 테스트를 깨지 않는지
- `docker compose config` — `IMAGE_TAG` 미설정 시 실패, 설정 시 해당 SHA로 치환되는지 확인
- develop 머지 후 CD 관찰 — 헬스 검증과 스모크 테스트 통과, EC2에서 실행 중인 이미지 태그가 SHA인지 확인
- **음성 검증**: 머지 실행(M1)이 성공한 뒤 develop에 다음 커밋(M2)이 들어가 그 배포까지 끝나면, **M1의 `deploy` 잡만 재실행**한다. M1은 이번에 강화된 워크플로 파일(D3 가드 포함)을 그대로 쓰고, M1의 SHA는 이제 `origin/develop` 끝(M2)이 아니므로 `docker compose pull` 이전에 가드가 막는다. 기대 결과: 실패, 로그에 "배포 중단: 이 실행의 커밋 ... 는 develop 최신 ... 이 아니다."

  옛 실행을 재실행하는 방식은 쓰지 않는다. GitHub Actions의 재실행은 **그 실행이 처음 트리거됐을 때 커밋에 있던 워크플로 파일**을 쓴다. 사고를 낸 옛 실행(`30418077779`) 시점의 `cd.yml`에는 가드가 없으므로, 재실행하면 옛 스크립트가 `IMAGE_TAG` 없이 `docker compose pull`을 돌려 `:latest`로 컨테이너를 교체하고, 헬스 대기 자체가 없어 초록으로 끝난다 — 검증이 안 될 뿐 아니라 실제로 위험하다.

## 6. 남는 것

- **롤백은 수동이다.** 헬스 검증이 실패하면 잡이 실패하고 이전 컨테이너는 교체된 상태로 남는다. 자동 롤백은 넣지 않는다 — 스키마가 앞서 나간 배포에서 코드만 되돌리면 이번과 같은 기동 실패가 재현되기 때문이다. SHA 태그가 고정되어 있으므로 복구는 이전 SHA를 지정해 재배포하는 것으로 한다.

  단, 이 브랜치 **이전** SHA의 이미지에는 curl이 없다. 그 이미지로 롤백하면 컨테이너 자체는 정상 동작하지만 healthcheck는 영구 `unhealthy`로 잡힌다(앱은 살아 있다). 게다가 D3 가드는 "develop 끝과 같은가"로 판정하므로, CD 파이프라인으로 옛 SHA를 재배포하는 것 자체가 불가능하다 — 복구는 ① develop에 revert 커밋을 넣어 새 배포를 트리거하거나, ② EC2에서 `IMAGE_TAG=<sha> docker compose up -d`를 직접 실행한다.
- **헬스 검증 대기 시간**: 컨테이너 쪽 실질 상한은 app 기준 `start_period 30s` + `interval 10s` × `retries 12` = 150초. 배포 스크립트의 서비스당 175초(5초 간격 35회)는 그 위에 여유를 더한 값이다. 첫 배포에서 실측치를 보고 조정될 수 있다.
- EC2에서의 수동 조작은 태그를 생략해도 안전하다 — 배포 스크립트가 매번 `.env`에 마지막 배포 SHA를 기록해 두기 때문이다(D1). 특정 버전을 강제하려면 `IMAGE_TAG=<sha> docker compose up -d`로 명시한다.
- **이미지 정리 정책**: `docker image prune -af --filter "until=72h"`를 헬스 게이트 **앞**(컨테이너 교체 직후)에서 돌린다. 게이트 뒤에 두면 게이트가 실패할 때마다 정리가 아예 돌지 않아, 디스크가 차서 헬스가 DOWN이 되는 상황을 스스로 벗어날 수 없다. SHA 태그를 쓰는 한 사용하지 않는 옛 이미지도 태그를 계속 들고 있어 dangling으로 잡히지 않으므로 `-a`가 필요하고, 사용 중인 이미지는 `-a`를 줘도 정리 대상에서 제외된다. `until=72h`는 최근 사흘 안에 배포된 이미지(롤백 후보)를 보존하기 위한 여유다.
- **다이제스트 고정은 이번 범위 밖의 후속 과제다.** 태그 고정(`:$DEPLOY_SHA`)보다 더 엄격한 형태는 다이제스트 고정(`@sha256:...`)이다. 같은 태그라도 다시 push되면 다른 내용이 올라갈 수 있다(예: 베이스 이미지가 그 사이 바뀌면 소스가 같아도 바이너리가 달라진다). `docker/build-push-action`의 digest 출력을 잡 output으로 넘기면 구현할 수 있다.
