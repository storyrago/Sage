-- 데모 계정 (비번 demo1234, BCrypt strength 10). provider=LOCAL.
INSERT INTO members (id, email, password, nickname, provider, created_at) VALUES
  (1, 'demo@demo.com',  '$2y$10$./I/HRInW7FBcewDKVwYCumiJMCjP2bILGD4jT6jTwf6ihDGdxmiK', '데모',   'LOCAL', NOW(6)),
  (2, 'guest@demo.com', '$2y$10$./I/HRInW7FBcewDKVwYCumiJMCjP2bILGD4jT6jTwf6ihDGdxmiK', '게스트', 'LOCAL', NOW(6));

-- 방
INSERT INTO chatrooms (id, name, created_at) VALUES
  (1, '공지', NOW(6)),
  (2, '잡담', NOW(6));

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
