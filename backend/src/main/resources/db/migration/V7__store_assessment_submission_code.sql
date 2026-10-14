CREATE TABLE assessment_submission_code (
  submission_id BIGINT NOT NULL,
  code_text LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(submission_id),
  CONSTRAINT fk_submission_code_submission FOREIGN KEY(submission_id) REFERENCES assessment_submission(id) ON DELETE CASCADE
);
