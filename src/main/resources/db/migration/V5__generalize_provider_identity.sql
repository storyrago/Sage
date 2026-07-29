-- 신원을 provider 중립으로 일반화하고 이메일을 선택 속성으로 전환
ALTER TABLE members MODIFY email VARCHAR(255) NULL;
ALTER TABLE members ADD COLUMN provider_id VARCHAR(255) NULL;

-- 기존 구글 회원의 신원 값 이관.
-- provider도 함께 맞춘다: 이전 연결 경로는 google_sub만 채우고 provider를 'LOCAL'로 남겼기 때문에,
-- google_sub이 있는 행은 provider 값과 무관하게 구글 신원으로 조회되어야 한다
UPDATE members SET provider_id = google_sub, provider = 'GOOGLE' WHERE google_sub IS NOT NULL;

ALTER TABLE members DROP INDEX uk_members_google_sub;
ALTER TABLE members DROP COLUMN google_sub;
ALTER TABLE members ADD CONSTRAINT uk_members_provider UNIQUE (provider, provider_id);
