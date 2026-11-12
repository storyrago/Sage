-- 읽은 위치는 last_read_seq(V12)로만 관리한다. V12 백필에 쓴 메시지 id 기준 읽음 컬럼을 지운다.
ALTER TABLE chatroom_members DROP COLUMN last_read_message_id;
