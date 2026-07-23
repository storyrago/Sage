-- V1 baseline: 현재 스키마 전체.
-- 기존 운영 DB는 flyway baseline-on-migrate로 이 버전을 "이미 적용됨"으로 마크(재실행 안 함) → 데이터 안전.
-- 신규 DB(로컬/테스트)에서만 실제로 실행되어 스키마를 생성한다.

CREATE TABLE members (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(255) NOT NULL,
    password          VARCHAR(255) NOT NULL,
    nickname          VARCHAR(10),
    profile_image_url VARCHAR(500),
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_email (email)
) ENGINE=InnoDB;

CREATE TABLE chatrooms (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE messages (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    content     VARCHAR(500) NOT NULL,
    image_url   VARCHAR(500),
    member_id   BIGINT,
    chatroom_id BIGINT,
    created_at  DATETIME(6)  NOT NULL,
    reply_to_id BIGINT,
    edited_at   DATETIME(6),
    deleted     BIT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_messages_member   FOREIGN KEY (member_id)   REFERENCES members (id),
    CONSTRAINT fk_messages_chatroom FOREIGN KEY (chatroom_id) REFERENCES chatrooms (id),
    CONSTRAINT fk_messages_reply    FOREIGN KEY (reply_to_id) REFERENCES messages (id)
) ENGINE=InnoDB;

CREATE TABLE chatroom_members (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    member_id   BIGINT,
    chatroom_id BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chatroom_members_member_chatroom (member_id, chatroom_id),
    CONSTRAINT fk_crm_member   FOREIGN KEY (member_id)   REFERENCES members (id),
    CONSTRAINT fk_crm_chatroom FOREIGN KEY (chatroom_id) REFERENCES chatrooms (id)
) ENGINE=InnoDB;
