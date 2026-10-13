CREATE TABLE oj_test_case_result (
  submission_id BIGINT NOT NULL,
  position INT NOT NULL,
  verdict VARCHAR(32) NOT NULL,
  time_ms INT NOT NULL,
  memory_kb INT NOT NULL,
  safe_error VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(submission_id,position),
  CONSTRAINT fk_oj_test_case_result_submission FOREIGN KEY(submission_id) REFERENCES assessment_submission(id) ON DELETE CASCADE,
  CONSTRAINT ck_oj_test_case_result_position CHECK(position BETWEEN 1 AND 100),
  CONSTRAINT ck_oj_test_case_result_verdict CHECK(verdict IN ('ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','RUNTIME_ERROR','COMPILATION_ERROR','INTERNAL_ERROR')),
  CONSTRAINT ck_oj_test_case_result_time CHECK(time_ms >= 0),
  CONSTRAINT ck_oj_test_case_result_memory CHECK(memory_kb >= 0)
);
