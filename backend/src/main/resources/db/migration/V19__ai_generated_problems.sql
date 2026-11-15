-- AI 出题：题目来源与归属。
--
-- source 区分人工作题（导入/后台创建）与 AI 生成，前端据此打「AI 生成」标签；
-- owner_member_id 只在 AI 生成时填，指向出题的学生，用于「我的题库」过滤。
-- 存量题目一律按 MANUAL 处理，owner 为空表示公共题库。
ALTER TABLE assessment_problem
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER test_data_locator,
  ADD COLUMN owner_member_id BIGINT NULL AFTER source,
  ADD CONSTRAINT ck_assessment_problem_source CHECK (source IN ('MANUAL','AI')),
  ADD CONSTRAINT fk_assessment_problem_owner FOREIGN KEY (owner_member_id) REFERENCES member(id),
  ADD KEY idx_assessment_problem_owner (owner_member_id, status, id);
