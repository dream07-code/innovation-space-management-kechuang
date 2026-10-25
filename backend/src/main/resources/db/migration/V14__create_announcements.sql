-- 站内公告：学院级（direction_id IS NULL）与方向级（direction_id 非空）。
CREATE TABLE announcement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(120) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    direction_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    withdrawn_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_announcement_direction FOREIGN KEY (direction_id) REFERENCES direction (id),
    CONSTRAINT fk_announcement_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    KEY idx_announcement_created (created_at)
);
