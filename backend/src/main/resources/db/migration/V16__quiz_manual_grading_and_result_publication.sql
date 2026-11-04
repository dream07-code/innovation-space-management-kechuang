-- V16：填空人工阅卷与成绩发布。
-- 设计见 docs/superpowers/specs/2026-11-04-quiz-manual-grading-design.md §7.1。

ALTER TABLE assessment
  ADD COLUMN results_published_at DATETIME(6) NULL,
  ADD COLUMN results_published_by BIGINT NULL,
  ADD CONSTRAINT fk_assessment_results_publisher
    FOREIGN KEY (results_published_by) REFERENCES app_user (id);

ALTER TABLE assessment_answer_sheet_item
  ADD COLUMN auto_awarded INT NOT NULL DEFAULT 0,
  ADD COLUMN manual_score INT NULL,
  ADD COLUMN review_comment VARCHAR(500) NULL,
  ADD COLUMN reviewed_by BIGINT NULL,
  ADD COLUMN reviewed_at DATETIME(6) NULL,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT fk_sheet_item_reviewer
    FOREIGN KEY (reviewed_by) REFERENCES app_user (id),
  ADD CONSTRAINT ck_sheet_item_manual_score
    CHECK (manual_score IS NULL OR manual_score >= 0),
  ADD CONSTRAINT ck_sheet_item_review_state CHECK (
    (manual_score IS NULL AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR (manual_score IS NOT NULL AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL));

-- 历史明细：交卷时的得分就是自动分；人工分留空。
UPDATE assessment_answer_sheet_item SET auto_awarded = awarded;

-- 已归档的历史考核视为成绩已发布，避免升级后学生突然看不到旧成绩。
UPDATE assessment
   SET results_published_at = updated_at
 WHERE status = 'ARCHIVED'
   AND results_published_at IS NULL;
