-- V12 混合试卷：客观题库、考核条目（替代 assessment_problem_link）、答题卡。
-- 设计口径见 docs/superpowers/specs/2026-10-24-mixed-exam-quiz-design.md §5。

CREATE TABLE quiz_question (
  id BIGINT NOT NULL AUTO_INCREMENT,
  type VARCHAR(16) NOT NULL,
  stem TEXT NOT NULL,
  options JSON NULL,
  answer JSON NOT NULL,
  analysis VARCHAR(1000) NULL,
  tags JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  KEY idx_quiz_question_status_type(status,type),
  CONSTRAINT fk_quiz_question_creator FOREIGN KEY(created_by) REFERENCES app_user(id),
  CONSTRAINT ck_quiz_question_type CHECK(type IN ('SINGLE_CHOICE','MULTI_CHOICE','TRUE_FALSE','FILL_BLANK')),
  CONSTRAINT ck_quiz_question_status CHECK(status IN ('DRAFT','PUBLISHED','RETIRED'))
);

CREATE TABLE assessment_item (
  assessment_id BIGINT NOT NULL,
  position INT NOT NULL,
  item_type VARCHAR(8) NOT NULL,
  problem_id BIGINT NULL,
  question_id BIGINT NULL,
  score INT NOT NULL,
  PRIMARY KEY(assessment_id,position),
  UNIQUE KEY uk_item_problem(assessment_id,problem_id),
  UNIQUE KEY uk_item_question(assessment_id,question_id),
  CONSTRAINT fk_item_assessment FOREIGN KEY(assessment_id) REFERENCES assessment(id),
  CONSTRAINT fk_item_problem FOREIGN KEY(problem_id) REFERENCES assessment_problem(id),
  CONSTRAINT fk_item_question FOREIGN KEY(question_id) REFERENCES quiz_question(id),
  CONSTRAINT ck_item_type CHECK(item_type IN ('PROGRAM','QUIZ')),
  CONSTRAINT ck_item_score CHECK(score BETWEEN 1 AND 100),
  CONSTRAINT ck_item_exactly_one_ref CHECK(
    (item_type = 'PROGRAM' AND problem_id IS NOT NULL AND question_id IS NULL)
    OR (item_type = 'QUIZ' AND question_id IS NOT NULL AND problem_id IS NULL)
  )
);

CREATE TABLE assessment_answer_sheet (
  id BIGINT NOT NULL AUTO_INCREMENT,
  assessment_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  answers JSON NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  submitted_at DATETIME(6) NULL,
  total_score INT NULL,
  graded_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_answer_sheet(assessment_id,member_id),
  KEY idx_answer_sheet_status(status),
  CONSTRAINT fk_sheet_assessment FOREIGN KEY(assessment_id) REFERENCES assessment(id),
  CONSTRAINT fk_sheet_member FOREIGN KEY(member_id) REFERENCES member(id),
  CONSTRAINT ck_sheet_status CHECK(status IN ('DRAFT','SUBMITTED'))
);

CREATE TABLE assessment_answer_sheet_item (
  sheet_id BIGINT NOT NULL,
  position INT NOT NULL,
  question_id BIGINT NOT NULL,
  answer JSON NULL,
  correct TINYINT NOT NULL,
  awarded INT NOT NULL,
  PRIMARY KEY(sheet_id,position),
  CONSTRAINT fk_sheet_item_sheet FOREIGN KEY(sheet_id) REFERENCES assessment_answer_sheet(id) ON DELETE CASCADE,
  CONSTRAINT fk_sheet_item_question FOREIGN KEY(question_id) REFERENCES quiz_question(id),
  CONSTRAINT ck_sheet_item_awarded CHECK(awarded >= 0)
);

-- 旧挂题关系全量搬入条目表：分值 100 兼容旧口径（每题满分 100，历史总分不变）。
INSERT INTO assessment_item(assessment_id,position,item_type,problem_id,score)
SELECT assessment_id,position,'PROGRAM',problem_id,100
FROM assessment_problem_link
ORDER BY assessment_id,position;

DROP TABLE assessment_problem_link;
