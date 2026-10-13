CREATE TABLE assessment_problem_assessment_test_case (
  id BIGINT NOT NULL AUTO_INCREMENT,
  problem_id BIGINT NOT NULL,
  position INT NOT NULL,
  input MEDIUMTEXT NOT NULL,
  expected_output MEDIUMTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_assessment_test_case_position(problem_id, position),
  CONSTRAINT fk_assessment_test_case_problem FOREIGN KEY(problem_id) REFERENCES assessment_problem(id) ON DELETE CASCADE,
  CONSTRAINT ck_assessment_test_case_position CHECK(position BETWEEN 1 AND 100)
);

CREATE TABLE assessment_problem_practice_test_case (
  id BIGINT NOT NULL AUTO_INCREMENT,
  problem_id BIGINT NOT NULL,
  position INT NOT NULL,
  input MEDIUMTEXT NOT NULL,
  expected_output MEDIUMTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_practice_test_case_position(problem_id, position),
  CONSTRAINT fk_practice_test_case_problem FOREIGN KEY(problem_id) REFERENCES assessment_problem(id) ON DELETE CASCADE,
  CONSTRAINT ck_practice_test_case_position CHECK(position BETWEEN 1 AND 100)
);
