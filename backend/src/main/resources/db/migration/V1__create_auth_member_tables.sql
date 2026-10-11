CREATE TABLE direction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_direction_code (code),
    UNIQUE KEY uk_direction_name (name)
);

CREATE TABLE member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_no VARCHAR(64) NOT NULL,
    direction_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    major VARCHAR(128) NULL,
    class_name VARCHAR(128) NULL,
    grade INT NULL,
    contact VARCHAR(128) NULL,
    membership_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    joined_at DATETIME(6) NOT NULL,
    membership_started_at DATETIME(6) NOT NULL,
    removed_at DATETIME(6) NULL,
    removal_reason VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_student_no (student_no),
    KEY idx_member_direction_status (direction_id, membership_status),
    CONSTRAINT fk_member_direction FOREIGN KEY (direction_id) REFERENCES direction (id),
    CONSTRAINT ck_member_status CHECK (membership_status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT ck_member_version CHECK (version >= 1)
);

CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NULL,
    account VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    display_name VARCHAR(128) NOT NULL,
    auth_revision BIGINT NOT NULL DEFAULT 1,
    last_login_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_account (account),
    UNIQUE KEY uk_app_user_member (member_id),
    CONSTRAINT fk_app_user_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_user_role CHECK (role IN ('COLLEGE_ADMIN', 'DIRECTION_LEADER', 'STUDENT')),
    CONSTRAINT ck_user_identity CHECK ((role = 'COLLEGE_ADMIN' AND member_id IS NULL) OR (role <> 'COLLEGE_ADMIN' AND member_id IS NOT NULL)),
    CONSTRAINT ck_user_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_user_revisions CHECK (version >= 1 AND auth_revision >= 1)
);

CREATE TABLE leader_direction_scope (
    user_id BIGINT NOT NULL,
    direction_id BIGINT NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    granted_by BIGINT NULL,
    PRIMARY KEY (user_id, direction_id),
    CONSTRAINT fk_scope_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_scope_direction FOREIGN KEY (direction_id) REFERENCES direction (id),
    CONSTRAINT fk_scope_granted_by FOREIGN KEY (granted_by) REFERENCES app_user (id)
);

CREATE TABLE auth_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    access_token_hash BINARY(32) NOT NULL,
    refresh_token_hash BINARY(32) NULL,
    refresh_family_id CHAR(36) NULL,
    session_type VARCHAR(32) NOT NULL,
    auth_revision BIGINT NOT NULL,
    access_expires_at DATETIME(6) NOT NULL,
    refresh_expires_at DATETIME(6) NULL,
    rotated_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    revoke_reason VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_access_hash (access_token_hash),
    UNIQUE KEY uk_session_refresh_hash (refresh_token_hash),
    KEY idx_session_user_status (user_id, revoked_at, access_expires_at),
    KEY idx_session_refresh_family (refresh_family_id),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_session_type CHECK (session_type IN ('NORMAL', 'PASSWORD_CHANGE')),
    CONSTRAINT ck_session_refresh CHECK ((session_type = 'PASSWORD_CHANGE' AND refresh_token_hash IS NULL AND refresh_family_id IS NULL AND refresh_expires_at IS NULL) OR (session_type = 'NORMAL' AND refresh_token_hash IS NOT NULL AND refresh_family_id IS NOT NULL AND refresh_expires_at IS NOT NULL))
);

CREATE TABLE api_idempotency (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    normalized_path VARCHAR(512) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    response_status INT NULL,
    response_body JSON NULL,
    resource_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_request (actor_user_id, http_method, normalized_path, idempotency_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (actor_user_id) REFERENCES app_user (id)
);

CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NULL,
    reason VARCHAR(1000) NULL,
    detail JSON NULL,
    summary VARCHAR(1000) NOT NULL,
    result VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_occurred (occurred_at),
    KEY idx_audit_resource (resource_type, resource_id),
    CONSTRAINT fk_audit_user FOREIGN KEY (actor_user_id) REFERENCES app_user (id)
);
