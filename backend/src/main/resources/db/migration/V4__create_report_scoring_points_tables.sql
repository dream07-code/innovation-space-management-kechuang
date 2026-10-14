
CREATE TABLE report (
 id BIGINT NOT NULL AUTO_INCREMENT, member_id BIGINT NOT NULL, direction_id BIGINT NOT NULL,
 report_type VARCHAR(16) NOT NULL, report_date DATE NULL, week_start DATE NULL, week_end DATE NULL,
 status VARCHAR(16) NOT NULL, current_version BIGINT NOT NULL DEFAULT 1, first_submitted_at DATETIME(6) NULL,
 score_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED', final_score INT NULL, score_reason VARCHAR(1000) NULL,
 awarded_points INT NOT NULL DEFAULT 0, editable_until DATETIME(6) NULL, rubric_version VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(id),
 UNIQUE KEY uk_report_daily (member_id, report_type, report_date), UNIQUE KEY uk_report_weekly (member_id, report_type, week_start),
 KEY idx_report_scope (direction_id,status,report_type), CONSTRAINT fk_report_member FOREIGN KEY(member_id) REFERENCES member(id), CONSTRAINT fk_report_direction FOREIGN KEY(direction_id) REFERENCES direction(id),
 CONSTRAINT ck_report_type CHECK(report_type IN ('DAILY','WEEKLY')), CONSTRAINT ck_report_status CHECK(status IN ('DRAFT','SUBMITTED')),
 CONSTRAINT ck_score_status CHECK(score_status IN ('NOT_REQUESTED','QUEUED','PROCESSING','SCORED','RETRYING','REVIEW_REQUIRED','MANUAL','STALE')),
 CONSTRAINT ck_report_score CHECK(final_score IS NULL OR final_score BETWEEN 1 AND 3)
);
CREATE TABLE report_version (
 id BIGINT NOT NULL AUTO_INCREMENT, report_id BIGINT NOT NULL, version BIGINT NOT NULL, content_json JSON NOT NULL,
 content_hash BINARY(32) NOT NULL, edited_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, is_current BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(id), UNIQUE KEY uk_report_version(report_id,version), CONSTRAINT fk_report_version_report FOREIGN KEY(report_id) REFERENCES report(id), CONSTRAINT fk_report_version_user FOREIGN KEY(edited_by) REFERENCES app_user(id)
);
CREATE TABLE report_scoring_task (
 id BIGINT NOT NULL AUTO_INCREMENT, report_id BIGINT NOT NULL, report_version_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 provider VARCHAR(64) NULL, model VARCHAR(128) NULL, rubric_version VARCHAR(64) NULL, attempt INT NOT NULL DEFAULT 0,
 score INT NULL, reason VARCHAR(1000) NULL, safe_error_code VARCHAR(64) NULL, next_retry_at DATETIME(6) NULL,
 started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_scoring_active(report_version_id), KEY idx_scoring_claim(status,next_retry_at), CONSTRAINT fk_scoring_report FOREIGN KEY(report_id) REFERENCES report(id), CONSTRAINT fk_scoring_version FOREIGN KEY(report_version_id) REFERENCES report_version(id), CONSTRAINT ck_scoring_status CHECK(status IN ('QUEUED','PROCESSING','SCORED','RETRYING','REVIEW_REQUIRED','FAILED','STALE'))
);
CREATE TABLE point_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT, member_id BIGINT NOT NULL, report_id BIGINT NULL, report_version_id BIGINT NULL, delta INT NOT NULL,
 source VARCHAR(32) NOT NULL, business_effective_at DATETIME(6) NOT NULL, recorded_at DATETIME(6) NOT NULL, rule_version VARCHAR(64) NULL, event_key VARCHAR(255) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_point_event(event_key), KEY idx_points_member_effective(member_id,business_effective_at), CONSTRAINT fk_points_member FOREIGN KEY(member_id) REFERENCES member(id), CONSTRAINT fk_points_report FOREIGN KEY(report_id) REFERENCES report(id), CONSTRAINT fk_points_version FOREIGN KEY(report_version_id) REFERENCES report_version(id), CONSTRAINT ck_points_source CHECK(source IN ('SUBMISSION_BASE','AI_ADJUSTMENT','MANUAL_ADJUSTMENT','REVERSAL'))
);
CREATE TABLE report_review (
 id BIGINT NOT NULL AUTO_INCREMENT, report_id BIGINT NOT NULL, report_version_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 original_score INT NULL, resolved_score INT NULL, reason VARCHAR(1000) NULL, resolved_by BIGINT NULL, version BIGINT NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL, resolved_at DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_report_review_version(report_version_id), CONSTRAINT fk_review_report FOREIGN KEY(report_id) REFERENCES report(id), CONSTRAINT fk_review_version FOREIGN KEY(report_version_id) REFERENCES report_version(id), CONSTRAINT fk_review_user FOREIGN KEY(resolved_by) REFERENCES app_user(id), CONSTRAINT ck_review_status CHECK(status IN ('PENDING','RESOLVED','STALE'))
);
