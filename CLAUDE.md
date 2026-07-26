# CLAUDE.md

## PR 작성 형식 (고정)

PR 본문은 항상 `.github/pull_request_template.md`의 섹션을 **그대로, 같은 순서·같은 제목으로** 채운다.
섹션 이름을 바꾸거나 즉석에서 새 섹션을 만들지 않는다.

- 해당 없는 섹션은 지우지 말고 **"없음"** 이라고 적는다.
- 리뷰어가 놓치면 위험한 변경(설정 한 줄로 기능 전체가 죽는 류)이 있을 때만
  `## 리뷰어가 꼭 봐야 할 변경`을 **`## 검증` 바로 앞에** 추가한다. 그 외 임시 섹션 금지.
- `## 검증`에는 **실제로 실행한 것만** 쓴다. 테스트 수치·빌드 결과·E2E 시나리오.
  안 한 검증은 안 했다고 명시한다.
- `gh pr create --body-file`은 GitHub 템플릿을 **우회**하므로, 그 경로로 만들 때도
  이 규칙이 유일한 강제 수단이다. 반드시 위 템플릿 구조로 본문을 작성할 것.
- PR 본문·커밋 메시지·코드 주석은 변경의 목적("무엇을·왜")만 쓴다.
  "누락/핫픽스/이미 배포됨/그래서 깨져 있었다" 같은 배경 서사·회고는 넣지 않는다.

## 브랜치·머지

- feature 브랜치 → **develop** 으로 PR. main 직접 타겟 금지.
- 머지는 사용자가 한다. PR을 쌓지 않는다(스택하면 자동 close됨).

## 스키마 변경

- **Flyway 사용 중**(`src/main/resources/db/migration/V*.sql`, `ddl-auto: validate`).
  스키마 변경은 마이그레이션 파일로만 넣는다. **수동 ALTER 금지** — 배포 시 자동 적용된다.
- 테스트는 H2 create-drop이라 Flyway 비활성(`src/test/resources/application.yaml`).

## 검증 명령

- 백엔드: `./gradlew test`
- 프론트: `cd frontend && npm run lint && npm run build` (유닛 테스트 러너 없음)

## 환경변수 / 배포

- 새 환경변수는 EC2 `.env`(값) + `docker-compose.yml` 해당 서비스의 `environment`(전달)
  **양쪽**에 넣는다. compose `environment`는 명시 목록이라 `.env`에만 두면 컨테이너로 전달되지 않는다.

## 작업 방식

- 계획·설계·리뷰는 **Opus 4.8 이상**(또는 Fable 5), 구현은 **Sonnet 서브에이전트**로 진행한다.
  시니어 백엔드(10년차) 관점으로 판단한다.
