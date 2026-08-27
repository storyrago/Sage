-- 소프트 삭제 표현을 시각 하나로 통일한다.
-- 메시지는 불리언이라 언제 지워졌는지 남지 않았고, 방(chatrooms.deleted_at)과 표현이 달랐다.
ALTER TABLE messages ADD COLUMN deleted_at DATETIME(6) NULL;

-- 과거 삭제 시각은 어디에도 남아 있지 않다. 마이그레이션 시각으로 채운다.
-- created_at보다 항상 뒤이므로 순서는 깨지지 않지만, 이 세대의 값은 근사치다.
UPDATE messages SET deleted_at = NOW(6) WHERE deleted = 1;

ALTER TABLE messages DROP COLUMN deleted;

-- 이 컬럼은 "만든 사람"이 아니라 승계로 값이 바뀌는 "현재 방장"이다. 이름을 의미에 맞춘다.
ALTER TABLE chatrooms DROP FOREIGN KEY fk_chatrooms_created_by;
ALTER TABLE chatrooms RENAME COLUMN created_by TO owner_id;
ALTER TABLE chatrooms
    ADD CONSTRAINT fk_chatrooms_owner FOREIGN KEY (owner_id) REFERENCES members (id);
