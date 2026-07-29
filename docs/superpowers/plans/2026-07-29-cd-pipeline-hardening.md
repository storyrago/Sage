# CD 파이프라인 — 배포 단위 고정과 기동 검증 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포되는 이미지·설정을 커밋 SHA 하나로 고정하고, 앱이 실제로 UP인 것을 확인한 뒤에만 CD를 성공으로 처리한다.

**Architecture:** compose의 `image:` 태그를 `${IMAGE_TAG}`로 파라미터화하고 배포 스크립트가 `github.sha`를 넣는다. EC2는 compose 파일도 같은 SHA에서 체크아웃하며, 그 SHA가 `origin/develop` 끝이 아니면 배포하지 않는다. 앱 컨테이너에 `healthcheck`(actuator `/actuator/health`)를 달고, 배포 스크립트가 `healthy`를 기다렸다가 실패하면 앱 로그를 남기고 잡을 실패시킨다.

**Tech Stack:** GitHub Actions / Docker Compose / Spring Boot Actuator / nginx / EC2(Ubuntu)

설계 문서: `docs/superpowers/specs/2026-07-29-cd-pipeline-hardening-design.md`

## Global Constraints

- 브랜치는 `chore/cd-pin-image-sha`(이미 존재, `origin/develop`에서 분기). PR 대상은 **develop**. main 직접 타겟 금지.
- 커밋 메시지·코드 주석은 변경의 목적만 쓴다. "누락/핫픽스/깨져 있었다" 같은 배경 서사 금지.
- 백엔드 검증 명령은 `./gradlew test`. 테스트는 **Redis(localhost:6379)와 MySQL이 떠 있어야** 통과한다(기존 테스트가 이미 그렇다. CI는 `ci.yml`의 services로 제공).
- 스키마 변경 없음. Flyway 마이그레이션 파일을 추가하지 않는다.
- 셸 스크립트에서 `set -e`를 쓸 때 `[ 조건 ] && break` 형태를 쓰지 않는다. AND-OR 리스트 전체가 non-zero로 끝나면 셸이 종료된다. `if ...; then break; fi`로 쓴다.
- PR 본문은 `.github/pull_request_template.md`의 섹션을 그대로, 같은 순서·같은 제목으로 채운다. 해당 없는 섹션은 "없음".

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `build.gradle` | actuator 의존성 | 수정 |
| `config/SecurityConfig.java` | `/actuator/health` 비인증 허용 | 수정 |
| `src/test/java/.../health/HealthEndpointTest.java` | 헬스가 인증 없이 UP을 반환하는지 | 생성 |
| `Dockerfile` | 컨테이너 헬스체크용 curl | 수정 |
| `docker-compose.yml` | 이미지 태그 파라미터화, app healthcheck | 수정 |
| `.github/workflows/cd.yml` | SHA 가드·SHA 체크아웃·IMAGE_TAG·헬스 대기 | 수정 |
| `frontend/nginx.conf` | `/actuator` SPA 폴백 차단 | 수정 |

---

## Task 1: `/actuator/health` 공개 (TDD)

**Files:**
- Create: `src/test/java/com/example/springboot_realtimechat/health/HealthEndpointTest.java`
- Modify: `build.gradle`
- Modify: `src/main/java/com/example/springboot_realtimechat/config/SecurityConfig.java:43-50`

**Interfaces:**
- Produces: `GET /actuator/health` → 200 `{"status":"UP"}`, 인증 불필요. Task 2의 컨테이너 `healthcheck`와 Task 3의 배포 검증이 이 엔드포인트에 의존한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/example/springboot_realtimechat/health/HealthEndpointTest.java`를 만든다:

```java
package com.example.springboot_realtimechat.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 배포 파이프라인이 기동 성공을 판정하는 근거다. 인증 없이 열려 있어야 컨테이너 헬스체크가 동작한다.
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 헬스는_인증_없이_UP을_반환한다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*HealthEndpointTest'`
Expected: FAIL. 상태 200을 기대했지만 401(또는 403)이 온다 — actuator 엔드포인트가 없고, `SecurityConfig`의 `anyRequest().authenticated()`가 모든 미등록 경로를 막는다.

- [ ] **Step 3: actuator 의존성을 추가한다**

`build.gradle`의 `dependencies` 블록에서 `spring-boot-starter-web` 줄 아래에 추가한다:

```gradle
	//actuator (배포 기동 검증용 헬스 엔드포인트)
	implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

- [ ] **Step 4: 아직 실패하는 것을 확인한다**

Run: `./gradlew test --tests '*HealthEndpointTest'`
Expected: 여전히 FAIL, 401(또는 403). 엔드포인트는 생겼지만 시큐리티가 막는다. 이 단계를 건너뛰지 말 것 — 두 원인이 각각 있다는 것을 확인하는 지점이다.

- [ ] **Step 5: 헬스만 permitAll에 넣는다**

`SecurityConfig.java`의 `requestMatchers(...)` 목록 마지막 항목(`"/login/oauth2/**"`) 뒤에 한 줄 추가한다. 쉼표 위치에 주의한다:

```java
                        .requestMatchers(
                                "/api/auth/**",      // 로그인
                                "/ws/**",            // WebSocket 핸드셰이크 (인증은 STOMP CONNECT에서)
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/oauth2/**",        // OAuth 진입
                                "/login/oauth2/**",  // OAuth 콜백
                                "/actuator/health"   // 배포 기동 검증 (health만, 다른 actuator 경로는 인증 필요)
                        ).permitAll()
```

`/actuator/**`가 아니라 `/actuator/health` 하나만 연다. 나머지 actuator 경로는 `anyRequest().authenticated()`에 걸린 채로 둔다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew test --tests '*HealthEndpointTest'`
Expected: PASS

Run: `./gradlew test`
Expected: 전체 통과. (Redis·MySQL이 떠 있어야 한다. 통과 개수를 기록해 둔다 — PR 본문에 쓴다.)

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/com/example/springboot_realtimechat/config/SecurityConfig.java src/test/java/com/example/springboot_realtimechat/health/HealthEndpointTest.java
git commit -m "feat(cd): 배포 기동 검증용 헬스 엔드포인트 공개"
```

---

## Task 2: 이미지 태그 파라미터화와 컨테이너 헬스체크

**Files:**
- Modify: `Dockerfile`
- Modify: `docker-compose.yml:3`, `docker-compose.yml:25`, `docker-compose.yml` app 서비스 블록

**Interfaces:**
- Consumes: Task 1의 `GET /actuator/health`
- Produces:
  - compose 변수 `IMAGE_TAG` — 미설정 시 `latest`로 대체된다. Task 3의 배포 스크립트가 이 변수를 export한다.
  - `app` 서비스의 컨테이너 헬스 상태(`docker inspect -f '{{.State.Health.Status}}'` → `starting` | `healthy` | `unhealthy`). Task 3이 이 값을 폴링한다.

- [ ] **Step 1: 앱 이미지에 curl을 넣는다**

`Dockerfile`을 아래로 교체한다. `eclipse-temurin:17-jre`에는 curl도 wget도 없어서 컨테이너 헬스체크를 실행할 수단이 없다:

```dockerfile
FROM eclipse-temurin:17-jre
# 컨테이너 헬스체크(compose healthcheck)가 쓸 curl
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: compose의 이미지 태그를 파라미터화하고 healthcheck를 단다**

`docker-compose.yml`의 `app` 서비스를 아래로 교체한다. `environment` 블록은 기존 내용을 그대로 두고, `image:` 한 줄과 `healthcheck:` 블록만 바뀐다:

```yaml
  app:
    image: ghcr.io/storyrago/realtimechat-backend-app:${IMAGE_TAG:-latest}
    build: .
    restart: unless-stopped
    expose:
      - "8080"
    environment:
      SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}
      SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
      SPRING_DATA_REDIS_HOST: redis
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
      JWT_SECRET: ${JWT_SECRET}
      GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID}         # OAuth: Google 클라이언트 자격증명 (값은 .env)
      GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET}
      KAKAO_CLIENT_ID: ${KAKAO_CLIENT_ID}           # OAuth: Kakao REST API 키 (값은 .env)
      KAKAO_CLIENT_SECRET: ${KAKAO_CLIENT_SECRET}
      FRONTEND_URL: ${FRONTEND_URL}                 # OAuth 성공 후 JWT 핸드오프 대상 프론트 주소
    healthcheck:                                    # 배포 스크립트가 이 상태로 기동 성공을 판정한다
      test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 30s
    depends_on:
      - redis
```

`web` 서비스의 `image:` 줄도 바꾼다:

```yaml
    image: ghcr.io/storyrago/realtimechat-backend-web:${IMAGE_TAG:-latest}
```

`web`의 `depends_on: - app`은 건드리지 않는다. `condition: service_healthy`로 바꾸면 앱이 죽었을 때 사이트가 통째로 안 뜬다.

- [ ] **Step 3: 태그 치환을 확인한다**

Run: `docker compose config 2>/dev/null | grep "image:"`
Expected: `IMAGE_TAG`가 없으므로 `...-app:latest`, `...-web:latest`, `redis:7` 세 줄.

Run: `IMAGE_TAG=deadbeef docker compose config 2>/dev/null | grep "image:"`
Expected: `...-app:deadbeef`, `...-web:deadbeef`, `redis:7`.

(`.env`가 없어 다른 변수는 빈 값으로 경고가 나오는데 stderr로 가므로 무시한다.)

- [ ] **Step 4: 이미지에 curl이 들어갔는지 확인한다**

Run: `./gradlew build -x test`
Expected: `build/libs/`에 jar 생성.

Run: `docker build -t rtc-app-local .`
Expected: 성공.

Run: `docker run --rm --entrypoint curl rtc-app-local --version`
Expected: `curl 8.x.x ...` 버전 문자열 출력.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile docker-compose.yml
git commit -m "feat(cd): 이미지 태그 파라미터화와 app 컨테이너 헬스체크"
```

---

## Task 3: 배포 잡 — SHA 가드·SHA 체크아웃·IMAGE_TAG·기동 검증

**Files:**
- Modify: `.github/workflows/cd.yml:68-87` (deploy 잡 전체)

**Interfaces:**
- Consumes: Task 2의 `IMAGE_TAG` 변수와 `app` 컨테이너 헬스 상태
- Produces: 없음 (파이프라인 종단)

- [ ] **Step 1: deploy 잡을 교체한다**

`.github/workflows/cd.yml`의 `deploy:` 잡(파일 끝까지)을 아래로 교체한다. `build-and-push` 잡은 그대로 둔다 — `:latest`와 `:${{ github.sha }}` 두 태그를 계속 push한다:

```yaml
  # 2) EC2는 빌드 없이 pull만 → 순식간. 1GB 서버 OOM/타임아웃 원천 제거.
  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to EC2 via SSH
        uses: appleboy/ssh-action@v1
        env:
          DEPLOY_SHA: ${{ github.sha }}
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          command_timeout: 10m
          envs: DEPLOY_SHA                          # 러너의 DEPLOY_SHA를 원격 셸로 전달
          script: |
            set -e                                  # 한 명령이라도 실패하면 즉시 중단 → 잡 실패로 표면화
            cd ~/realtimechat-backend
            git fetch origin

            # 배포 대상이 develop 끝이 아니면 배포하지 않는다.
            # 큐 대기·재실행으로 완료 순서가 뒤집혀도 옛 커밋이 배포되지 못한다.
            TIP=$(git rev-parse origin/develop)
            if [ "$TIP" != "$DEPLOY_SHA" ]; then
              echo "배포 중단: 이 실행의 커밋 $DEPLOY_SHA 는 develop 최신 $TIP 이 아니다."
              exit 1
            fi

            git reset --hard "$DEPLOY_SHA"          # compose 파일도 배포 대상 커밋에서 가져온다
            export IMAGE_TAG="$DEPLOY_SHA"          # 이 실행이 빌드한 이미지만 받는다

            docker compose pull                     # 해당 SHA 태그만 받기 (실패 시 여기서 중단, 기존 컨테이너 유지)
            docker compose up -d --remove-orphans   # 빌드 없이 새 이미지로 교체

            # app이 healthy가 될 때까지 대기 (5초 간격 35회 = 최대 2분 55초)
            CID=$(docker compose ps -q app)
            STATUS=""
            for i in $(seq 1 35); do
              STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$CID" 2>&1) || STATUS="inspect_failed"
              if [ "$STATUS" = healthy ]; then break; fi
              if [ "$STATUS" = unhealthy ]; then
                echo "기동 실패: app 컨테이너가 unhealthy"
                docker compose logs --tail=50 app
                exit 1
              fi
              sleep 5
            done
            if [ "$STATUS" != healthy ]; then
              echo "기동 검증 타임아웃: app 상태=$STATUS"
              docker compose logs --tail=50 app
              exit 1
            fi

            docker image prune -f                   # 안 쓰는 옛 이미지 정리 (디스크 확보)
```

`[ "$STATUS" = healthy ] && break`로 줄이지 말 것. `set -e` 아래에서 조건이 거짓이면 AND-OR 리스트 전체가 non-zero가 되어 셸이 종료된다.

- [ ] **Step 2: YAML이 유효한지 확인한다**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/cd.yml')); print('yaml ok')"`
Expected: `yaml ok`

- [ ] **Step 3: 원격 셸 스크립트의 문법을 확인한다**

워크플로에서 스크립트 본문만 뽑아 `bash -n`으로 검사한다:

```bash
python3 -c "
import yaml
d = yaml.safe_load(open('.github/workflows/cd.yml'))
open('/tmp/cd-script.sh','w').write(d['jobs']['deploy']['steps'][0]['with']['script'])
print('script extracted')
" && bash -n /tmp/cd-script.sh && echo "bash syntax ok"
```

Expected: `script extracted` / `bash syntax ok`

- [ ] **Step 4: 가드 로직을 로컬에서 한 번 돌려본다**

실제 배포 없이 조건 분기만 검증한다:

```bash
TIP=abc123; DEPLOY_SHA=abc123; if [ "$TIP" != "$DEPLOY_SHA" ]; then echo "중단"; else echo "진행"; fi
TIP=abc123; DEPLOY_SHA=old999; if [ "$TIP" != "$DEPLOY_SHA" ]; then echo "중단"; else echo "진행"; fi
```

Expected: 첫 줄 `진행`, 둘째 줄 `중단`

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/cd.yml
git commit -m "feat(cd): 배포 대상 SHA 고정과 기동 검증"
```

---

## Task 4: nginx에서 `/actuator` 폴백 차단

**Files:**
- Modify: `frontend/nginx.conf` (443 server 블록)

**Interfaces:**
- Consumes: 없음
- Produces: 외부 `GET /actuator/health` → 404

- [ ] **Step 1: location 블록을 추가한다**

`frontend/nginx.conf`의 443 server 블록에서 `location /api/ {` 바로 위에 넣는다:

```nginx
  # 관리 엔드포인트는 외부에 열지 않는다.
  # 프록시 규칙이 없으면 SPA 폴백이 걸려 200 HTML이 나가고, 앱이 죽어도 살아있는 것처럼 보인다.
  location /actuator/ {
    return 404;
  }
  location = /actuator {
    return 404;
  }
```

nginx는 선언 순서와 무관하게 가장 긴 접두사를 고르므로 위치는 가독성 문제일 뿐이다. 컨테이너 헬스체크는 컨테이너 안에서 `localhost:8080`으로 직접 호출하므로 이 규칙의 영향을 받지 않는다.

- [ ] **Step 2: 웹 이미지가 빌드되는지 확인한다**

Run: `docker build -t rtc-web-local ./frontend`
Expected: 성공.

이 단계에서 확인되는 것은 빌드 성공까지다. `nginx -t`는 Let's Encrypt 인증서 경로(`/etc/letsencrypt/...`)가 로컬에 없어 실패하므로 돌리지 않는다. 설정이 실제로 먹는지는 Task 5의 배포 후 확인으로 검증한다.

- [ ] **Step 3: Commit**

```bash
git add frontend/nginx.conf
git commit -m "feat(cd): 관리 엔드포인트의 SPA 폴백 차단"
```

---

## Task 5: PR과 실배포 검증

**Files:** 없음 (검증·PR 단계)

**Interfaces:**
- Consumes: Task 1~4의 커밋 전부

- [ ] **Step 1: 전체 테스트를 다시 돌린다**

Run: `./gradlew test`
Expected: 전체 통과. 통과 개수를 기록한다.

- [ ] **Step 2: 브랜치를 푸시한다**

```bash
git push -u origin chore/cd-pin-image-sha
```

- [ ] **Step 3: PR을 만든다**

`.github/pull_request_template.md`의 섹션을 그대로 쓴다. 본문 예시(검증 수치는 실제 실행 결과로 채운다):

```markdown
## 개요

배포되는 이미지·설정을 커밋 SHA 하나로 고정하고, 앱이 UP인 것을 확인한 뒤에만 CD를 성공으로 처리한다.
설계: `docs/superpowers/specs/2026-07-29-cd-pipeline-hardening-design.md`

## 변경 내용

**인프라**
- compose의 `image:`를 `${IMAGE_TAG:-latest}`로 파라미터화. 배포 스크립트가 `github.sha`를 넣어, 배포 잡이 자기가 빌드한 이미지만 받는다.
- EC2에서 compose 파일을 `origin/develop`이 아니라 배포 대상 SHA로 체크아웃한다.
- 배포 대상이 develop 끝이 아니면 배포를 중단한다.
- `app` 컨테이너에 `healthcheck`를 달고, 배포 스크립트가 `healthy`를 기다린다. `unhealthy`·타임아웃이면 앱 로그 50줄을 남기고 잡을 실패시킨다.
- nginx가 `/actuator/`에 404를 반환한다. 프록시 규칙이 없으면 SPA 폴백으로 200 HTML이 나간다.

**백엔드**
- actuator 추가, `/actuator/health`만 비인증 허용.

## 검증

- `./gradlew test` — N/N 통과
- `docker compose config` — `IMAGE_TAG` 미설정 시 `:latest`, `IMAGE_TAG=deadbeef` 시 `:deadbeef`로 치환됨을 확인
- `docker build` 후 `docker run --rm --entrypoint curl` — 앱 이미지에 curl 존재 확인
- 워크플로 YAML 파싱 + 원격 스크립트 `bash -n` 통과
- 실배포 검증(머지 후 수행) — 아래 "배포 영향" 참고

## 배포 영향

- 스키마 변경 없음. 환경변수 추가 없음.
- 이 PR이 머지되는 배포부터 새 파이프라인이 적용된다.
- EC2에서 수동으로 `docker compose up -d`를 실행할 때는 `IMAGE_TAG=<sha>`를 명시해야 한다. 생략하면 `:latest`로 뜬다.

## 구현 노트 / 알려진 한계

- 자동 롤백은 넣지 않았다. 스키마가 앞서 나간 배포에서 코드만 되돌리면 기동 실패가 재현된다. SHA 태그가 고정되어 있으므로 복구는 이전 SHA를 지정해 재배포한다.
- 짧은 간격으로 두 번 푸시하면 뒤처진 실행은 SHA 가드에 막혀 실패한다. "배포하지 않았다"는 신호로 의도한 동작이다.
- 헬스 대기 상한은 2분 30초. 앱 기동이 더 느려지면 조정이 필요하다.
```

Run: `gh pr create --base develop --head chore/cd-pin-image-sha --title "feat(cd): 배포 단위 SHA 고정과 기동 검증" --body-file <작성한 본문 파일>`

- [ ] **Step 4: 머지는 사용자가 한다 — 체크포인트**

여기서 멈추고 사용자에게 머지를 요청한다. PR을 쌓지 않는다.

- [ ] **Step 5: 머지 후 CD를 관찰한다**

Run: `gh run list --workflow=cd.yml --limit 3`
Expected: 머지 커밋에 대한 실행이 success. `deploy` 잡 로그에 헬스 대기 후 정상 종료가 보인다.

- [ ] **Step 6: 배포된 것이 그 커밋인지 확인한다**

EC2에서 실행:

```bash
docker compose ps
docker inspect --format '{{.Config.Image}}' $(docker compose ps -q app)
```

Expected: `app`의 상태가 `healthy`. 이미지 태그가 `:latest`가 아니라 머지 커밋 SHA.

- [ ] **Step 7: 외부에서 동작을 확인한다**

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://sagertc.duckdns.org/actuator/health
curl -s -o /dev/null -w "%{http_code}\n" https://sagertc.duckdns.org/api/chatrooms
```

Expected: 첫 줄 `404`(폴백 차단됨), 둘째 줄 `302`(앱 정상).

- [ ] **Step 8: 가드가 실제로 막는지 확인한다 (음성 검증)**

옛 커밋 실행의 **deploy 잡만** 재실행한다. 잡 ID를 먼저 찾는다:

```bash
gh run view 30418077779 --json jobs --jq '.jobs[] | "\(.databaseId) \(.name)"'
```

deploy 잡 ID로 재실행한다:

```bash
gh run rerun 30418077779 --job <deploy 잡 ID>
```

Expected: 실패. 로그에 `배포 중단: 이 실행의 커밋 ... 는 develop 최신 ... 이 아니다.` 가드는 `docker compose pull` 이전에 동작하므로 실제 배포에 영향이 없다.

`--job` 없이 실행 전체를 재실행하지 말 것. `build-and-push`가 함께 돌아 옛 커밋 이미지가 `:latest`를 다시 덮어쓴다. 배포 경로는 더 이상 `:latest`를 보지 않지만, 수동 조작의 기본값이 옛 코드가 된다.

- [ ] **Step 9: 결과를 PR에 남긴다**

Step 5~8의 실제 결과(헬스 상태, 배포된 이미지 태그, 두 curl의 상태 코드, 가드 실패 로그 한 줄)를 PR 코멘트로 남긴다. 실행하지 않은 검증은 쓰지 않는다.
