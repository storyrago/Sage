-- 온보딩 경험 여부. NULL이면 아직 온보딩 화면을 지나지 않은 회원
ALTER TABLE members ADD COLUMN onboarded_at DATETIME(6) NULL;

-- 사용자가 직접 정하는 값이므로 10자에서 20자로 늘린다
ALTER TABLE members MODIFY nickname VARCHAR(20);

-- 마이그레이션 시점의 기존 회원은 온보딩 대상이 아니다
UPDATE members SET onboarded_at = NOW(6);
