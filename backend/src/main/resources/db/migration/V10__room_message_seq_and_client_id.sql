-- 방 안에서 1부터 빈틈없이 증가하는 메시지 순번.
-- 클라이언트가 순서 정렬과 누락 감지에 쓰고, 이벤트 소비자가 방 단위 순서를 판단하는 기준이 된다.
-- 마지막 발급 값은 방 행에 두고, 방 행 쓰기 잠금 아래에서만 증가시킨다.
ALTER TABLE chatrooms ADD COLUMN last_message_seq BIGINT NOT NULL DEFAULT 0;

ALTER TABLE messages ADD COLUMN seq BIGINT NULL;

-- 클라이언트가 전송마다 만드는 UUID. 재전송을 같은 메시지로 식별한다. 구버전 클라이언트는 비워 보낸다.
ALTER TABLE messages ADD COLUMN client_message_id VARCHAR(36) NULL;

-- 기존 메시지는 방마다 id 순서로 순번을 매긴다. id는 삽입 순서이므로 지금까지 보이던 순서와 같다.
UPDATE messages m
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY chatroom_id ORDER BY id) AS rn FROM messages) ranked
    ON m.id = ranked.id
SET m.seq = ranked.rn;

UPDATE chatrooms c
    JOIN (SELECT chatroom_id, MAX(seq) AS max_seq FROM messages GROUP BY chatroom_id) last
    ON c.id = last.chatroom_id
SET c.last_message_seq = last.max_seq;

ALTER TABLE messages MODIFY seq BIGINT NOT NULL;

ALTER TABLE messages ADD CONSTRAINT uk_messages_room_seq UNIQUE (chatroom_id, seq);

-- member_id가 NULL(탈퇴자 익명화)이거나 client_message_id가 NULL인 행은 MySQL 유니크에서 서로 겹치지 않는다.
ALTER TABLE messages ADD CONSTRAINT uk_messages_member_client_id UNIQUE (member_id, client_message_id);
