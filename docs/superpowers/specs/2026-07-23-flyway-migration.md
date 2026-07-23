# Flyway 도입 — 기존 운영 DB baseline

- 날짜: 2026-07-23
- 브랜치: `feat/flyway-migration` (develop 분기)
- 범위: DB 스키마 변경을 코드(리포)로 관리 — 수동 `ALTER TABLE` 종말. 기존 운영 스키마는 baseline으로 안전 편입.

## 문제

메시지 수정/삭제 때 컬럼 추가를 **RDS에 손으로 `ALTER TABLE`** 쳐야 했다(안 하면 `ddl-auto: validate`라 앱이 부팅 실패). 인덱스 추가 등 앞으로의 변경도 매번 수동 → 사고·누락 위험.

## 설계

- **기존 스키마를 V1 baseline으로 인정**: `baseline-on-migrate=true`, `baseline-version=1`. 운영 DB엔 이미 스키마가 있으니 Flyway가 V1을 **실행하지 않고** "적용됨"으로 마크만 한다(데이터 안전). 이후 변경만 `V2, V3...`로 자동 적용.
- `V1__baseline.sql` = 현재 전체 스키마(members·chatrooms·messages[edited_at·deleted 포함]·chatroom_members[unique]). 신규 DB(로컬/테스트)에서만 실제 실행.
- `ddl-auto: validate` **유지** — Flyway가 스키마를 관리하고, Hibernate는 엔티티와 맞는지 검증만.
- 테스트는 H2 `create-drop` 그대로 두고 `spring.flyway.enabled=false`(테스트에서 Flyway 미개입 → 기존 테스트 무영향).

## 겪은 것 (Boot 4의 함정)

- **Spring Boot 4는 auto-configuration이 기술별 모듈로 쪼개졌다.** `flyway-core`만 넣으니 Flyway가 **아예 안 돌았다**(로그도 없음) → Hibernate validate가 빈 DB 보고 `missing table [chatroom_members]`로 실패.
- 원인: Boot 4에선 Flyway 통합 auto-config가 **`org.springframework.boot:spring-boot-flyway`** 모듈에 있고, 이게 `flyway-core`로 딸려오지 않는다. (JPA도 `spring-boot-hibernate`/`spring-boot-jpa`처럼 모듈화됨.)
- 해결: `spring-boot-flyway` 의존성 추가 → 그제야 Flyway auto-config 활성.

## 결정 정정 — DB 인덱스는 안 넣음

당초 V2로 `messages(chatroom_id, id)` 인덱스를 넣으려 했으나 **중복**이라 제외. InnoDB에서 FK(`chatroom_id`) 인덱스는 클러스터드 PK(`id`)를 내부에 포함해 물리적으로 `(chatroom_id, id)`가 되므로, keyset 쿼리(`WHERE chatroom_id=? AND id<? ORDER BY id DESC`)를 이미 완벽히 커버한다. 무의미한 인덱스 추가 지양 → 이번엔 **V1 baseline만**.

## 검증 (실측, 로컬 실제 MySQL 9.6)

- **fresh DB**: 빈 DB 부팅 → Flyway가 V1 적용(`Successfully applied 1 migration, now at v1`) → 앱 정상 부팅 = **Hibernate validate 통과(V1이 엔티티와 정확히 일치)**.
- **baseline 시나리오(=운영과 동일)**: 스키마가 이미 있는 DB 부팅 → Flyway `Successfully baselined schema with version: 1` → `No migration necessary` → **V1 재실행 안 함** → 정상 부팅. `flyway_schema_history`에 `type=BASELINE, version=1`.
- `./gradlew test` 전체 통과(H2, Flyway off).

## 배포 안전성

운영 첫 배포 시 Flyway는 `flyway_schema_history` 테이블 생성 + V1 baseline 레코드만 남긴다(스키마 변경 없음). validate는 기존 운영 스키마 대상이라 그대로 통과. **수동 개입 불필요.** 앞으로 스키마 변경은 `V2__xxx.sql` 파일만 커밋하면 배포 때 자동 적용.
