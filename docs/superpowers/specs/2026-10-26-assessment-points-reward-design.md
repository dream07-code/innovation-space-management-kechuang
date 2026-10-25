# 考核积分奖励 设计

日期：2026-10-26。用户需求：创建考核时可选是否加积分；第一名 20 分，其余按比例；详细规则授权实现方制定。

## 规则（用户授权制定）

- **开关**：创建/编辑考核时 `pointsEnabled`（默认 false，存量考核行为不变）。
- **结算时机**：考核 close 时一次性结算；此后人工调分不追溯积分。
- **分值公式**：`delta = round(20 × 本人总分 ÷ 全场最高分)`。
  - 口径与结果页「按学生汇总」totalScore 一致（程序题最终分之和 + 客观题答题卡得分）；
  - 并列第一同分自然并列（都 20）；最高分为 0 则整场不发；
  - 0 分成员得 0 分（含未作答）。
- **发放对象**：该考核所属方向的 ACTIVE 学生（与排行榜资格一致）。
- **幂等**：point_ledger.eventKey = `ASSESSMENT_REWARD:{assessmentId}:{memberId}`，重复 close 不重复发放。

## 数据与实现

- **V15 迁移**：
  1. `ALTER TABLE assessment ADD points_enabled BOOLEAN NOT NULL DEFAULT FALSE`；
  2. `point_ledger` 的 `ck_points_source` CHECK 增加枚举 `ASSESSMENT_REWARD`（先 DROP CHECK 再 ADD）。
- **后端**：
  - `AssessmentServiceImpl`：create/update 接受 `pointsEnabled`（布尔校验），response 输出；close 在 submitAllDrafts 后调用结算；
  - 新 `AssessmentPointsRewardService.settle(assessmentId)`：查 enabled → 计算每成员 totalScore（latest submission final_score SUM + answer_sheet total_score，仅 ACTIVE STUDENT）→ topScore>0 时逐人 `appendIfAbsent(member,null,null,delta,'ASSESSMENT_REWARD',eventKey,close 时刻)`；
  - `PointLedgerServiceImpl` 说明文案映射增加 `ASSESSMENT_REWARD → 考核奖励`。
- **契约**：Create/Update/Assessment schema 加 `pointsEnabled`；response 已含。前端 gen:api。
- **前端**：AssessmentEditView 开关「考核积分」+ 说明文案（第一名 20 分、其余按成绩比例、关闭时结算）；详情页显示「积分考核」标签。
- **测试**：后端 AssessmentPointsRewardIT（比例计算、并列第一、全零不发、开关关闭不发、close 幂等不重复、学生 ledger 可见含来源文案）；前端编辑页开关 spec。

## 不做

- 结算后调分追溯、参与保底分、独立奖励池上限、多批次结算。
