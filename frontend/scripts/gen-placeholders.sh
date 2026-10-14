#!/bin/sh
# 一次性生成占位视图。这些页面本轮只搭骨架，业务实现留待后续。
#
# 输出到 frontend/src/views/。用脚本自身位置推导前端根目录
# （scripts/ 的上一级），避免把产物写进 scripts/ 里。
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$SCRIPT_DIR/.." && pwd)"

gen() {
  dir="$1"; name="$2"; desc="$3"; endpoints="$4"; pending="${5:-false}"
  path="$BASE/views/$dir/$name.vue"
  mkdir -p "$BASE/views/$dir"
  {
    echo '<script setup lang="ts">'
    echo "// 占位骨架：业务实现待后续开发。"
    if [ "$pending" = "true" ]; then
      echo "// 该功能依赖尚未确认的业务规则，按契约要求只提供骨架，不启用真实执行。"
    fi
    echo "import PagePlaceholder from '@/components/PagePlaceholder.vue'"
    echo '</script>'
    echo ''
    echo '<template>'
    echo "  <PagePlaceholder"
    echo "    description=\"$desc\""
    if [ -n "$endpoints" ]; then
      echo "    :endpoints=\"[$endpoints]\""
    fi
    if [ "$pending" = "true" ]; then
      echo '    pending'
    fi
    echo '  />'
    echo '</template>'
  } > "$path"
  echo "  $dir/$name.vue"
}

echo "生成占位视图："

# -- 学员 / 负责人 ------------------------------------------------------------
gen member DashboardView "个人首页：查看方向、累计与本月积分、总排名与方向排名、当日日报与本周周报状态。" \
  "'GET /api/v1/auth/me', 'GET /api/v1/points/me', 'GET /api/v1/rankings/me', 'GET /api/v1/daily-reports', 'GET /api/v1/weekly-reports'"
gen member ProfileView "个人资料：仅查看本人信息。学生端没有资料修改入口，契约中学生不使用 PATCH /members。" \
  "'GET /api/v1/auth/me'"
gen member DailyReportListView "日报列表：筛选与分页查看本人的草稿与已提交日报，并轮询异步评分状态。" \
  "'GET /api/v1/daily-reports'" true
gen member DailyReportEditView "日报编辑：保存草稿、正式提交、查看评分与理由、在允许范围内修改、提出复核。" \
  "'POST /api/v1/daily-reports', 'GET /api/v1/daily-reports/{reportId}', 'PATCH /api/v1/daily-reports/{reportId}', 'POST /api/v1/daily-reports/{reportId}/submit'" true
gen member WeeklyReportListView "周报列表：按自然周查看本人的草稿与已提交周报。" \
  "'GET /api/v1/weekly-reports'" true
gen member WeeklyReportEditView "周报编辑：按自然周（北京时间周一至周日）撰写、提交与查看评分。" \
  "'POST /api/v1/weekly-reports', 'GET /api/v1/weekly-reports/{reportId}', 'PATCH /api/v1/weekly-reports/{reportId}', 'POST /api/v1/weekly-reports/{reportId}/submit'" true
gen member MyReviewsView "我的复核：查看本人报告提出的评分复核申请及其处理结果。" \
  "'GET /api/v1/score-reviews', 'POST /api/v1/score-reviews'" true
gen member MyPointsView "积分记录：本人累计与本月积分概览，以及逐条积分流水（含来源与业务生效时间）。" \
  "'GET /api/v1/points/me', 'GET /api/v1/points/ledger'"

# -- 共用 --------------------------------------------------------------------
gen shared RankingsView "排行榜：切换全空间或指定方向、累计榜或指定月份榜，并高亮本人名次。列表只展示契约允许的最小字段。" \
  "'GET /api/v1/rankings/overall', 'GET /api/v1/rankings/directions/{directionId}', 'GET /api/v1/rankings/me'" true

# -- 管理端 ------------------------------------------------------------------
gen admin AdminDashboardView "管理首页：全空间人数概览、待处理复核与失败任务提醒。" \
  "'GET /api/v1/members', 'GET /api/v1/score-reviews', 'GET /api/v1/tasks', 'GET /api/v1/system/rules'"
gen admin MemberListView "人员管理：按方向、姓名或学号、状态筛选并分页；新增学生（仅限获授权方向）、查看与修改资料、移出成员。" \
  "'GET /api/v1/members', 'POST /api/v1/members', 'PATCH /api/v1/members/{memberId}', 'POST /api/v1/members/{memberId}/remove'"
gen admin MemberDetailView "成员详情：查看资料与报告提交情况；学院管理员可更正学号、重置密码、移出或恢复成员资格。" \
  "'GET /api/v1/members/{memberId}', 'POST /api/v1/members/{memberId}/account-corrections', 'POST /api/v1/members/{memberId}/password-reset', 'POST /api/v1/members/{memberId}/remove'"
gen admin LeaderListView "负责人管理：任命现有在籍学生为方向负责人，调整其资料、负责方向与账号状态，或撤销任命。" \
  "'GET /api/v1/direction-leaders', 'POST /api/v1/direction-leaders', 'PATCH /api/v1/direction-leaders/{memberId}', 'POST /api/v1/direction-leaders/{memberId}/revoke'"
gen admin ReportManageView "报告管理：按方向、学生、日期区间与评分状态查看权限范围内的报告；管理员只看正式报告，负责人可看本人草稿。" \
  "'GET /api/v1/daily-reports', 'GET /api/v1/weekly-reports'"
gen admin ScoreReviewManageView "评分复核：查看复核申请，并按 1–3 分人工确定最终评分（需同时校验报告版本与复核版本）。" \
  "'GET /api/v1/score-reviews', 'POST /api/v1/score-reviews/{reviewId}/resolve'" true
gen admin PointLedgerView "积分流水：查看权限范围内的积分变动明细，含来源、关联报告与业务生效时间。" \
  "'GET /api/v1/points/ledger'"
gen admin RulesView "规则配置：查看当前生效的业务规则及各项确认状态；月度评定规则须确认齐全后才可启用。" \
  "'GET /api/v1/system/rules', 'PUT /api/v1/system/rules/monthly-assessment'" true
gen admin MonthlyAssessmentListView "月度评定：生成候选快照、查看参评人员与同分情况、确认完整候选名单并异步执行。" \
  "'GET /api/v1/monthly-assessments', 'POST /api/v1/monthly-assessments'" true
gen admin MonthlyAssessmentDetailView "评定详情：查看快照内容（规则版本、参评人员、分数与入选原因），处理同分并确认执行。" \
  "'GET /api/v1/monthly-assessments/{assessmentId}', 'POST /api/v1/monthly-assessments/{assessmentId}/confirm', 'POST /api/v1/monthly-assessments/{assessmentId}/execute'" true
gen admin TaskListView "自动任务：查看 AI 评分、未提交日报检测与月度评定任务的执行状态，并重试符合条件的失败任务。" \
  "'GET /api/v1/tasks', 'GET /api/v1/tasks/{taskId}', 'POST /api/v1/tasks/{taskId}/retry'" true
gen admin AuditListView "审计记录：查看脱敏后的管理员敏感操作记录，按资源与操作类型筛选。" \
  "'GET /api/v1/audits'"

echo "完成。"
