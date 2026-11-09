-- V17：AI 助教多会话与学习画像。
-- 只新增表，不改动任何既有表结构：会话/消息/画像都是助教自己的数据，
-- 判题与成绩仍以 assessment_submission / oj_judge_result / assessment_result 为准。

-- 会话：一个学生可以开多个话题；problem_id 为空表示纯知识点问答。
CREATE TABLE tutor_conversation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  problem_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  -- 列表按「最近更新」倒序取本人会话，索引前缀就是 member_id。
  KEY idx_tutor_conversation_member (member_id, updated_at),
  -- 管理员物理删除学生时，会话跟着走；否则外键会把「删成员」挡成 500。
  CONSTRAINT fk_tutor_conversation_member FOREIGN KEY (member_id) REFERENCES member (id)
    ON DELETE CASCADE,
  -- 题目被删时只断开关联，不删学生问过的对话；会话随后变成纯知识点问答。
  CONSTRAINT fk_tutor_conversation_problem FOREIGN KEY (problem_id)
    REFERENCES assessment_problem (id) ON DELETE SET NULL
);

-- 消息：只允许 user / assistant 两种角色；删会话时消息一起删，不留孤儿行。
CREATE TABLE tutor_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_tutor_message_conversation (conversation_id, id),
  CONSTRAINT fk_tutor_message_conversation FOREIGN KEY (conversation_id)
    REFERENCES tutor_conversation (id) ON DELETE CASCADE,
  CONSTRAINT ck_tutor_message_role CHECK (role IN ('user', 'assistant'))
);

-- 学习画像：每个学生一行聚合快照，由后端在读取画像时按提交/判题/得分重新算并覆盖写入。
-- 存快照而不是视图，是为了让「同一份数据读两次结果一致」，也便于以后做趋势对比。
CREATE TABLE tutor_learning_profile (
  member_id BIGINT NOT NULL,
  attempted_problems INT NOT NULL,
  passed_problems INT NOT NULL,
  pass_rate INT NOT NULL,
  submission_count INT NOT NULL,
  average_score INT NULL,
  last_submitted_at DATETIME(6) NULL,
  weak_points JSON NOT NULL,
  recommendations JSON NOT NULL,
  snapshot_at DATETIME(6) NOT NULL,
  PRIMARY KEY (member_id),
  CONSTRAINT fk_tutor_profile_member FOREIGN KEY (member_id) REFERENCES member (id)
    ON DELETE CASCADE,
  CONSTRAINT ck_tutor_profile_counts CHECK (
    attempted_problems >= 0
    AND passed_problems >= 0
    AND passed_problems <= attempted_problems
    AND pass_rate BETWEEN 0 AND 100
    AND submission_count >= 0),
  CONSTRAINT ck_tutor_profile_score CHECK (average_score IS NULL OR average_score BETWEEN 0 AND 100)
);
