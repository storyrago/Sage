-- 방 데이터를 비우고 주인이 있는 데모 방으로 다시 채운다.
-- V4 시드는 이미 적용된 마이그레이션이라 행을 지워도 재실행되지 않으므로,
-- 삭제와 재시드를 한 파일에서 함께 한다.
-- 회원은 건드리지 않는다(로그인 계정 유지). 참조가 끊긴 회원은 방 목록에 나타나지 않는다.

-- messages.reply_to_id는 자기 자신을 참조하므로 링크를 먼저 끊어야 행 단위 삭제가 통과한다.
UPDATE messages SET reply_to_id = NULL WHERE reply_to_id IS NOT NULL;
DELETE FROM messages;
DELETE FROM chatroom_bans;
DELETE FROM chatroom_members;
DELETE FROM chatrooms;

-- 주인 없는 방이 처음부터 생기지 않도록 created_by를 지정한다.
INSERT INTO chatrooms (id, name, created_at, created_by, is_private, invite_code, deleted_at) VALUES
  (1, '공지', NOW(6), 1, FALSE, NULL, NULL),
  (2, '잡담', NOW(6), 2, FALSE, NULL, NULL);

-- 방1(공지) 메시지 id 1~3
INSERT INTO messages (id, content, member_id, chatroom_id, created_at, deleted) VALUES
  (1, '샘플 채팅에 오신 걸 환영합니다.', 2, 1, NOW(6), 0),
  (2, '여기는 공지 채널이에요.',          1, 1, NOW(6), 0),
  (3, '무엇이든 편하게 남겨주세요.',      2, 1, NOW(6), 0);

-- 방2(잡담) 메시지 id 4~9 (5번은 4번 답장)
INSERT INTO messages (id, content, member_id, chatroom_id, created_at, reply_to_id, deleted) VALUES
  (4, '오늘 점심 뭐 먹지?',  2, 2, NOW(6), NULL, 0),
  (5, '김치찌개 어때요',     1, 2, NOW(6), 4,    0),
  (6, '좋아요',              2, 2, NOW(6), NULL, 0),
  (7, '2시에 회의 있어요',   2, 2, NOW(6), NULL, 0),
  (8, '넵 참고할게요',       1, 2, NOW(6), NULL, 0),
  (9, '다들 수고하셨습니다', 2, 2, NOW(6), NULL, 0);

-- 멤버십 + 읽음 포인터
--  demo(1): 공지 다 읽음(3), 잡담은 5까지만 읽음 → 잡담 안읽음 = id>5 & 남이 보냄 & !삭제 = {6,7,9} = 3
--  guest(2): 둘 다 다 읽음
INSERT INTO chatroom_members (id, member_id, chatroom_id, last_read_message_id) VALUES
  (1, 1, 1, 3),
  (2, 2, 1, 3),
  (3, 1, 2, 5),
  (4, 2, 2, 9);
