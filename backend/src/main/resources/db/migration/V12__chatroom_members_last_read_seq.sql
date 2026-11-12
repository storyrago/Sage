-- 읽은 위치를 방 안 순번으로 저장한다. 안읽음 수는 방 last_message_seq와의 차이로 계산한다.
-- last_read_message_id는 화면이 순번으로 옮겨 갈 때까지 함께 유지하고, 이후 별도 마이그레이션에서 지운다.
ALTER TABLE chatroom_members ADD COLUMN last_read_seq BIGINT NOT NULL DEFAULT 0;

-- 방 안에서 id와 seq는 같은 순서로 증가하므로, 포인터 이하 메시지의 최대 seq가 곧 읽은 위치다
-- (포인터가 가리키는 메시지가 없어도 성립). 본인이 보낸 메시지까지는 읽은 것으로 본다.
UPDATE chatroom_members cm
SET cm.last_read_seq = GREATEST(
        COALESCE((SELECT MAX(m.seq) FROM messages m
                  WHERE m.chatroom_id = cm.chatroom_id AND m.id <= cm.last_read_message_id), 0),
        COALESCE((SELECT MAX(m.seq) FROM messages m
                  WHERE m.chatroom_id = cm.chatroom_id AND m.member_id = cm.member_id), 0));
