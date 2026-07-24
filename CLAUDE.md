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
