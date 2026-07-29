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
`docker-compose.yml`의 `image:`를 `${IMAGE_TAG:-latest}`로 파라미터화하고, 배포 스크립트가 `IMAGE_TAG=$GITHUB_SHA`를 export한다. 배포 잡은 자기가 방금 빌드한 이미지만 받는다.

`:latest` push는 유지한다. 최신 표식으로 쓸모가 있고, 배포 경로가 더는 참조하지 않으므로 해롭지 않다.

*대가*: EC2에서 `IMAGE_TAG` 없이 `docker compose up -d`를 실행하면 `:latest`로 되돌아간다. 지원되는 배포 경로는 CD 스크립트뿐이며, 수동 조작은 태그를 명시해야 한다(§5).

**D2. compose 파일도 배포 대상 SHA에서 가져온다.**
`git reset --hard origin/develop` → `git reset --hard $GITHUB_SHA`. detached HEAD가 되지만 다음 배포가 다시 reset하므로 상태는 누적되지 않는다.

**D3. 배포 대상이 develop 최신이 아니면 중단한다.**
EC2 스크립트가 `git fetch` 직후 `git rev-parse origin/develop`과 `$GITHUB_SHA`를 비교하고, 다르면 사유를 출력하고 종료 코드 1로 끝낸다.

"조상인가"가 아니라 "끝과 같은가"로 검사한다. 사고를 낸 `b6d331d`는 develop의 조상이었으므로 조상 검사로는 걸러지지 않는다.

*대가*: 짧은 간격으로 두 번 푸시하면 뒤처진 실행이 빨간불이 된다. 이는 "이 실행은 배포하지 않았다"는 정직한 신호이므로 그대로 둔다. 일반적인 겹침은 기존 `concurrency`가 먼저 취소한다.

**D4. 기동 검증은 actuator 헬스로 한다.**
`spring-boot-starter-actuator`를 추가하고 `/actuator/health`를 `SecurityConfig`의 permitAll 목록에 넣는다. 현재 `anyRequest().authenticated()`이므로 빠뜨리면 헬스 체크가 401을 받는다.

기본 헬스 인디케이터에 DataSource·Redis가 포함되므로, 이번 같은 스키마 불일치는 기동 실패로 즉시 드러난다.

**D5. 헬스 체크는 컨테이너 `healthcheck`로 정의하고, 배포 스크립트는 그 상태를 기다린다.**
`Dockerfile`에 curl을 설치하고(`eclipse-temurin:17-jre`에 없다) compose `app`에 `healthcheck`를 선언한다. 배포 스크립트는 `docker inspect`로 `healthy`를 기다리며, `unhealthy`이거나 타임아웃이면 **앱 로그 마지막 50줄을 출력하고 잡을 실패시킨다.**

컨테이너에 헬스 상태를 남기는 방식이라 배포 이후에도 `docker compose ps`로 상태를 볼 수 있다. 배포 스크립트에서만 curl을 호출하면 그 순간의 판정만 남는다.

**D6. `web`의 `depends_on`은 바꾸지 않는다.**
`condition: service_healthy`를 걸면 앱이 죽었을 때 사이트 전체가 뜨지 않는다. 화면은 뜨고 API만 502인 편이 진단에 낫다.

**D7. nginx는 `/actuator`에 404를 반환한다.**
현재 `/actuator` 프록시 규칙이 없어 SPA 폴백이 걸리고, `/actuator/health`가 **200 HTML**을 반환한다. 앱이 죽어 있어도 200이라 오판을 부른다. 헬스를 외부로 열지는 않고, 폴백만 끊는다.

## 3. 변경 파일

| 파일 | 변경 |
|---|---|
| `docker-compose.yml` | `image:` 태그 파라미터화(D1), `app`에 `healthcheck`(D5) |
| `.github/workflows/cd.yml` | `envs`로 `GITHUB_SHA` 전달, SHA 검사(D3), SHA 체크아웃(D2), `IMAGE_TAG` export(D1), 헬스 대기·실패 처리(D5) |
| `Dockerfile` | curl 설치(D5) |
| `build.gradle` | `spring-boot-starter-actuator`(D4) |
| `SecurityConfig.java` | permitAll에 `/actuator/health`(D4) |
| `frontend/nginx.conf` | `location /actuator/ { return 404; }`(D7) |

## 4. 배포 스크립트 흐름

```
git fetch origin
origin/develop 끝 == $GITHUB_SHA ?  → 아니면 사유 출력 후 실패        (D3)
git reset --hard $GITHUB_SHA                                        (D2)
export IMAGE_TAG=$GITHUB_SHA                                        (D1)
docker compose pull        # 이 SHA 태그만 받는다. 실패 시 기존 컨테이너 유지
docker compose up -d --remove-orphans
app 컨테이너가 healthy 될 때까지 대기                                  (D5)
  → unhealthy·타임아웃: docker compose logs --tail=50 app 후 실패
docker image prune -f
```

## 5. 검증

- `./gradlew test` — actuator·Security 변경이 기존 테스트를 깨지 않는지
- `docker compose config` — `IMAGE_TAG` 치환 결과 확인(미설정 시 `:latest`, 설정 시 해당 SHA)
- develop 머지 후 CD 관찰 — 헬스 검증 통과, EC2에서 실행 중인 이미지 태그가 SHA인지 확인
- **음성 검증**: 사고를 낸 낡은 실행(`30418077779`)을 재실행해 D3 가드에 막혀 실패하는지 확인한다. 가드는 pull 이전에 동작하므로 배포에 영향이 없다

## 6. 남는 것

- **롤백은 수동이다.** 헬스 검증이 실패하면 잡이 실패하고 이전 컨테이너는 교체된 상태로 남는다. 자동 롤백은 넣지 않는다 — 스키마가 앞서 나간 배포에서 코드만 되돌리면 이번과 같은 기동 실패가 재현되기 때문이다. SHA 태그가 고정되어 있으므로 복구는 이전 SHA를 지정해 재배포하는 것으로 한다.
- **헬스 검증 대기 시간은 첫 배포에서 조정될 수 있다.** 초기값은 `start_period: 30s` + 10초 간격 12회(최대 약 2분 30초)로 둔다.
- EC2에서의 수동 조작은 `IMAGE_TAG=<sha> docker compose up -d` 형태로 태그를 명시한다(D1의 대가).
