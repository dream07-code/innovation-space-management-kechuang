CREATE TABLE assessment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(120) NOT NULL,
  description TEXT NOT NULL,
  direction_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  start_at DATETIME(6) NOT NULL,
  end_at DATETIME(6) NOT NULL,
  allowed_languages JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  KEY idx_assessment_status_time(status,start_at,end_at),
  CONSTRAINT fk_assessment_direction FOREIGN KEY(direction_id) REFERENCES direction(id),
  CONSTRAINT fk_assessment_creator FOREIGN KEY(created_by) REFERENCES app_user(id),
  CONSTRAINT ck_assessment_status CHECK(status IN ('DRAFT','PUBLISHED','CLOSED','ARCHIVED')),
  CONSTRAINT ck_assessment_window CHECK(end_at > start_at)
);

CREATE TABLE assessment_problem (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(120) NOT NULL,
  summary VARCHAR(300) NOT NULL,
  description TEXT NOT NULL,
  input_format TEXT NOT NULL,
  output_format TEXT NOT NULL,
  examples JSON NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  tags JSON NOT NULL,
  time_limit_ms INT NOT NULL,
  memory_limit_kb INT NOT NULL,
  allowed_languages JSON NOT NULL,
  test_data_locator VARCHAR(512) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  version BIGINT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  KEY idx_assessment_problem_status(status,difficulty),
  CONSTRAINT fk_assessment_problem_creator FOREIGN KEY(created_by) REFERENCES app_user(id),
  CONSTRAINT ck_assessment_problem_status CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT ck_assessment_problem_difficulty CHECK(difficulty IN ('EASY','MEDIUM','HARD')),
  CONSTRAINT ck_assessment_problem_time CHECK(time_limit_ms BETWEEN 100 AND 30000),
  CONSTRAINT ck_assessment_problem_memory CHECK(memory_limit_kb BETWEEN 1024 AND 1048576)
);

CREATE TABLE assessment_problem_link (
  assessment_id BIGINT NOT NULL,
  problem_id BIGINT NOT NULL,
  position INT NOT NULL,
  PRIMARY KEY(assessment_id,problem_id),
  UNIQUE KEY uk_assessment_problem_position(assessment_id,position),
  CONSTRAINT fk_assessment_link_assessment FOREIGN KEY(assessment_id) REFERENCES assessment(id),
  CONSTRAINT fk_assessment_link_problem FOREIGN KEY(problem_id) REFERENCES assessment_problem(id)
);

CREATE TABLE assessment_submission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  assessment_id BIGINT NOT NULL,
  problem_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  language VARCHAR(16) NOT NULL,
  code_locator VARCHAR(512) NULL,
  code_hash BINARY(32) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
  version BIGINT NOT NULL DEFAULT 1,
  submitted_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  KEY idx_assessment_submission_scope(assessment_id,problem_id,member_id,submitted_at),
  CONSTRAINT fk_submission_assessment FOREIGN KEY(assessment_id) REFERENCES assessment(id),
  CONSTRAINT fk_submission_problem FOREIGN KEY(problem_id) REFERENCES assessment_problem(id),
  CONSTRAINT fk_submission_member FOREIGN KEY(member_id) REFERENCES member(id),
  CONSTRAINT ck_submission_status CHECK(status IN ('RECEIVED','JUDGING','JUDGED','FAILED','NEEDS_REVIEW')),
  CONSTRAINT ck_submission_language CHECK(language IN ('java','python','cpp','javascript','go','rust'))
);

CREATE TABLE oj_judge_result (
  submission_id BIGINT NOT NULL,
  verdict VARCHAR(32) NOT NULL,
  passed_test_cases INT NOT NULL,
  total_test_cases INT NOT NULL,
  time_ms INT NOT NULL,
  memory_kb INT NOT NULL,
  safe_error VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(submission_id),
  CONSTRAINT fk_oj_result_submission FOREIGN KEY(submission_id) REFERENCES assessment_submission(id),
  CONSTRAINT ck_oj_verdict CHECK(verdict IN ('ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','RUNTIME_ERROR','COMPILATION_ERROR','INTERNAL_ERROR'))
);

CREATE TABLE ai_evaluation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  score INT NULL,
  reason VARCHAR(1000) NULL,
  model VARCHAR(128) NULL,
  rubric_version VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_ai_evaluation_version(submission_id,version),
  CONSTRAINT fk_ai_evaluation_submission FOREIGN KEY(submission_id) REFERENCES assessment_submission(id),
  CONSTRAINT ck_ai_evaluation_status CHECK(status IN ('PENDING','COMPLETE','FAILED','NEEDS_REVIEW')),
  CONSTRAINT ck_ai_evaluation_score CHECK(score IS NULL OR score BETWEEN 0 AND 100)
);

CREATE TABLE assessment_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  submission_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  score INT NOT NULL,
  comment VARCHAR(2000) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_assessment_review_version(submission_id,version),
  CONSTRAINT fk_review_submission FOREIGN KEY(submission_id) REFERENCES assessment_submission(id),
  CONSTRAINT fk_review_member FOREIGN KEY(member_id) REFERENCES member(id),
  CONSTRAINT fk_review_reviewer FOREIGN KEY(reviewer_id) REFERENCES app_user(id),
  CONSTRAINT ck_assessment_review_status CHECK(status IN ('ACTIVE','SUPERSEDED')),
  CONSTRAINT ck_assessment_review_score CHECK(score BETWEEN 0 AND 100)
);

CREATE TABLE assessment_result (
  submission_id BIGINT NOT NULL,
  technical_verdict VARCHAR(32) NOT NULL,
  passed_test_cases INT NOT NULL,
  total_test_cases INT NOT NULL,
  time_ms INT NOT NULL,
  memory_kb INT NOT NULL,
  ai_score INT NULL,
  human_score INT NULL,
  final_score INT NULL,
  score_source VARCHAR(16) NOT NULL,
  result_status VARCHAR(16) NOT NULL,
  published_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(submission_id),
  CONSTRAINT fk_assessment_result_submission FOREIGN KEY(submission_id) REFERENCES assessment_submission(id),
  CONSTRAINT ck_assessment_result_source CHECK(score_source IN ('HUMAN','AI','PENDING','UNAVAILABLE')),
  CONSTRAINT ck_assessment_result_status CHECK(result_status IN ('PENDING','COMPLETE','NEEDS_REVIEW'))
);

CREATE TABLE assessment_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id BIGINT NULL,
  action VARCHAR(128) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id BIGINT NULL,
  detail JSON NULL,
  summary VARCHAR(1000) NOT NULL,
  result VARCHAR(16) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  KEY idx_assessment_audit_resource(resource_type,resource_id,occurred_at),
  CONSTRAINT fk_assessment_audit_actor FOREIGN KEY(actor_user_id) REFERENCES app_user(id)
);
