-- 방의 주인. NULL이면 주인 없는 방(시드 방, 주인이 탈퇴한 방)이다.
ALTER TABLE chatrooms ADD COLUMN created_by BIGINT NULL;

-- 잠금 여부와 입장 코드. is_private=true 이고 invite_code IS NULL 이면
-- 아무도 새로 들어올 수 없는 동결 상태다.
ALTER TABLE chatrooms ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chatrooms ADD COLUMN invite_code VARCHAR(12) NULL;

-- 소프트 삭제 시각
ALTER TABLE chatrooms ADD COLUMN deleted_at DATETIME(6) NULL;

ALTER TABLE chatrooms
    ADD CONSTRAINT uk_chatrooms_invite_code UNIQUE (invite_code);
ALTER TABLE chatrooms
    ADD CONSTRAINT fk_chatrooms_created_by FOREIGN KEY (created_by) REFERENCES members (id);

-- 강퇴된 회원. 멤버십 행만 지우면 재입장으로 즉시 복구되므로 별도로 기록한다.
CREATE TABLE chatroom_bans (
    chatroom_id BIGINT      NOT NULL,
    member_id   BIGINT      NOT NULL,
    banned_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (chatroom_id, member_id),
    CONSTRAINT fk_bans_chatroom FOREIGN KEY (chatroom_id) REFERENCES chatrooms (id),
    CONSTRAINT fk_bans_member   FOREIGN KEY (member_id)   REFERENCES members (id)
);
