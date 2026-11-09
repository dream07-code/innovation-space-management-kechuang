/**
 * 路由表。
 *
 * 本轮只搭骨架：所有业务页面都是占位组件，认证相关的三个页面是真实实现。
 * 页面归属依据需求文档 §10 与契约 §5 的权限矩阵。
 *
 * meta 说明：
 *   - `public`：无需登录（登录页、阻断页、404）。
 *   - `roles`：允许访问的角色。缺省表示任意已登录角色。
 *   - `allowRestricted`：受限改密会话是否可访问（仅改密页为 true）。
 *   - `title`：页面标题，供文档标题与菜单使用。
 *   - `confirmed`：该功能依赖的业务规则是否已确认。false 表示只能展示骨架，
 *      不得启用真实执行（对应契约的 x-rule-status: requires-confirmation）。
 */
import type { RouteRecordRaw } from 'vue-router'
import type { Role } from '@/api/types'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    roles?: Role[]
    allowRestricted?: boolean
    title?: string
    /** 依赖的业务规则尚未确认，界面只能展示骨架。 */
    pending?: boolean
  }
}

/** 学员与方向负责人共用的页面。学院管理员没有成员身份，不参与。 */
const MEMBER_ROLES: Role[] = ['STUDENT', 'DIRECTION_LEADER']

/** 管理端页面：负责人可管理所辖方向，管理员可管理全量。 */
const STAFF_ROLES: Role[] = ['COLLEGE_ADMIN', 'DIRECTION_LEADER']

export const routes: RouteRecordRaw[] = [
  // -- 公开页面 --------------------------------------------------------------
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/blocked',
    name: 'blocked',
    component: () => import('@/views/auth/BlockedView.vue'),
    meta: { public: true, title: '访问受限' },
  },
  {
    // 单独的页面而非弹窗：受限改密会话是一类不同的会话，不能当正常业务会话用。
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/auth/ChangePasswordView.vue'),
    meta: { allowRestricted: true, title: '修改密码' },
  },

  // -- 学员 / 负责人的个人区域 ------------------------------------------------
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/member/DashboardView.vue'),
        meta: { roles: MEMBER_ROLES, title: '个人首页' },
      },
      {
        path: 'tutor',
        name: 'tutor',
        component: () => import('@/views/tutor/TutorChatView.vue'),
        meta: { roles: MEMBER_ROLES, title: 'AI 助教' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/member/ProfileView.vue'),
        meta: { roles: MEMBER_ROLES, title: '个人资料' },
      },
      {
        path: 'reports/daily',
        name: 'daily-reports',
        component: () => import('@/views/member/DailyReportListView.vue'),
        meta: { roles: MEMBER_ROLES, title: '日报' },
      },
      {
        path: 'reports/daily/new',
        name: 'daily-report-new',
        component: () => import('@/views/member/DailyReportEditView.vue'),
        meta: { roles: MEMBER_ROLES, title: '写日报' },
      },
      {
        path: 'reports/daily/:reportId',
        name: 'daily-report-detail',
        component: () => import('@/views/member/DailyReportEditView.vue'),
        meta: { roles: MEMBER_ROLES, title: '日报详情' },
      },
      {
        path: 'reports/weekly',
        name: 'weekly-reports',
        component: () => import('@/views/member/WeeklyReportListView.vue'),
        meta: { roles: MEMBER_ROLES, title: '周报' },
      },
      {
        path: 'reports/weekly/new',
        name: 'weekly-report-new',
        component: () => import('@/views/member/WeeklyReportEditView.vue'),
        meta: { roles: MEMBER_ROLES, title: '写周报' },
      },
      {
        path: 'reports/weekly/:reportId',
        name: 'weekly-report-detail',
        component: () => import('@/views/member/WeeklyReportEditView.vue'),
        meta: { roles: MEMBER_ROLES, title: '周报详情' },
      },
      {
        path: 'points',
        name: 'my-points',
        component: () => import('@/views/member/MyPointsView.vue'),
        meta: { roles: MEMBER_ROLES, title: '积分记录' },
      },
    ],
  },

  // -- OJ 考核（独立领域，不写入现有积分） ------------------------------------
  {
    path: '/oj',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      {
        path: 'assessments',
        name: 'oj-assessments',
        component: () => import('@/views/oj/AssessmentListView.vue'),
        meta: { title: 'OJ 考核' },
      },
      {
        path: 'assessments/new',
        name: 'oj-assessment-new',
        component: () => import('@/views/oj/AssessmentEditView.vue'),
        meta: { roles: STAFF_ROLES, title: '创建 OJ 考核' },
      },
      {
        path: 'assessments/:assessmentId',
        name: 'oj-assessment-detail',
        component: () => import('@/views/oj/AssessmentDetailView.vue'),
        meta: { title: 'OJ 考核详情' },
      },
      {
        path: 'assessments/:assessmentId/edit',
        name: 'oj-assessment-edit',
        component: () => import('@/views/oj/AssessmentEditView.vue'),
        meta: { roles: STAFF_ROLES, title: '编辑 OJ 考核' },
      },
      {
        path: 'assessments/:assessmentId/results',
        name: 'oj-assessment-results',
        component: () => import('@/views/oj/AssessmentResultsView.vue'),
        meta: { title: 'OJ 考核结果' },
      },
      {
        path: 'assessments/:assessmentId/quiz-review',
        name: 'oj-assessment-quiz-review',
        component: () => import('@/views/oj/QuizReviewView.vue'),
        meta: { roles: STAFF_ROLES, title: '填空阅卷' },
      },
      {
        path: 'assessments/:assessmentId/paper',
        name: 'oj-assessment-paper',
        component: () => import('@/views/oj/ExamPaperView.vue'),
        meta: { roles: MEMBER_ROLES, title: '考试试卷' },
      },
      {
        path: 'problems',
        name: 'oj-problems',
        component: () => import('@/views/oj/ProblemListView.vue'),
        meta: { title: 'OJ 题库' },
      },
      {
        path: 'problems/new',
        name: 'oj-problem-new',
        component: () => import('@/views/oj/ProblemEditView.vue'),
        meta: { roles: STAFF_ROLES, title: '创建 OJ 题目' },
      },
      {
        path: 'problems/:problemId',
        name: 'oj-problem-detail',
        component: () => import('@/views/oj/ProblemDetailView.vue'),
        meta: { title: 'OJ 题目详情' },
      },
      {
        path: 'problems/:problemId/edit',
        name: 'oj-problem-edit',
        component: () => import('@/views/oj/ProblemEditView.vue'),
        meta: { roles: STAFF_ROLES, title: '编辑 OJ 题目' },
      },
      {
        path: 'problems/:problemId/practice',
        name: 'oj-practice-code',
        component: () => import('@/views/oj/CodeSubmitView.vue'),
        meta: { roles: ['STUDENT'], title: 'OJ 题库练习' },
      },
      {
        path: 'assessments/:assessmentId/problems/:problemId/solve',
        name: 'oj-submit-code',
        component: () => import('@/views/oj/CodeSubmitView.vue'),
        meta: { roles: ['STUDENT'], title: 'OJ 代码提交' },
      },
      {
        path: 'submissions',
        name: 'oj-submissions',
        component: () => import('@/views/oj/SubmissionListView.vue'),
        meta: { title: 'OJ 提交记录' },
      },
      {
        path: 'submissions/:submissionId',
        name: 'oj-submission-detail',
        component: () => import('@/views/oj/SubmissionDetailView.vue'),
        meta: { title: 'OJ 提交详情' },
      },
      {
        path: 'submissions/:submissionId/review',
        name: 'oj-submission-review',
        component: () => import('@/views/oj/ReviewSubmissionView.vue'),
        meta: { roles: STAFF_ROLES, title: 'OJ 人工批阅' },
      },
      {
        // 批改报告对所有登录角色开放；学生只能看自己的提交，可见性由后端逐对象校验。
        path: 'submissions/:submissionId/report',
        name: 'oj-submission-report',
        component: () => import('@/views/oj/SubmissionReportView.vue'),
        meta: { title: 'AI 批改报告' },
      },
      {
        path: 'quiz-bank',
        name: 'oj-quiz-bank',
        component: () => import('@/views/oj/QuizQuestionListView.vue'),
        meta: { roles: STAFF_ROLES, title: '客观题库' },
      },
      {
        path: 'quiz-bank/new',
        name: 'oj-quiz-new',
        component: () => import('@/views/oj/QuizQuestionEditView.vue'),
        meta: { roles: STAFF_ROLES, title: '创建客观题' },
      },
      {
        path: 'quiz-bank/:questionId/edit',
        name: 'oj-quiz-edit',
        component: () => import('@/views/oj/QuizQuestionEditView.vue'),
        meta: { roles: STAFF_ROLES, title: '编辑客观题' },
      },
    ],
  },

  // -- 排行榜（三种角色都可看，但展示字段受限） --------------------------------
  {
    path: '/rankings',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      {
        path: '',
        name: 'rankings',
        component: () => import('@/views/shared/RankingsView.vue'),
        meta: { title: '排行榜' },
      },
    ],
  },

  // -- 管理端 ----------------------------------------------------------------
  {
    path: '/admin',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      {
        path: 'announcements',
        name: 'admin-announcements',
        component: () => import('@/views/admin/AnnouncementsManageView.vue'),
        meta: { roles: STAFF_ROLES, title: '公告管理' },
      },
      {
        path: '',
        name: 'admin-dashboard',
        component: () => import('@/views/admin/AdminDashboardView.vue'),
        meta: { roles: ['COLLEGE_ADMIN'], title: '管理首页' },
      },
      {
        path: 'members',
        name: 'admin-members',
        component: () => import('@/views/admin/MemberListView.vue'),
        meta: { roles: STAFF_ROLES, title: '人员管理' },
      },
      {
        path: 'members/new',
        name: 'admin-member-create',
        component: () => import('@/views/admin/MemberCreateView.vue'),
        meta: { roles: STAFF_ROLES, title: '添加学生' },
      },
      {
        path: 'members/batch-import',
        name: 'admin-member-batch-import',
        component: () => import('@/views/admin/MemberBatchImportView.vue'),
        meta: { roles: STAFF_ROLES, title: '批量导入学生' },
      },
      {
        path: 'members/:memberId',
        name: 'admin-member-detail',
        component: () => import('@/views/admin/MemberDetailView.vue'),
        meta: { roles: STAFF_ROLES, title: '成员详情' },
      },
      {
        path: 'leaders',
        name: 'admin-leaders',
        component: () => import('@/views/admin/LeaderListView.vue'),
        meta: { roles: ['COLLEGE_ADMIN'], title: '负责人管理' },
      },
      {
        path: 'reports/daily',
        name: 'admin-daily-reports',
        component: () => import('@/views/admin/ReportManageView.vue'),
        props: { reportType: 'DAILY' },
        meta: { roles: STAFF_ROLES, title: '日报管理' },
      },
      {
        path: 'reports/daily/review',
        name: 'admin-daily-report-review',
        component: () => import('@/views/admin/ReportReviewView.vue'),
        props: { reportType: 'DAILY' },
        meta: { roles: STAFF_ROLES, title: '日报批阅' },
      },
      {
        path: 'reports/weekly',
        name: 'admin-weekly-reports',
        component: () => import('@/views/admin/ReportManageView.vue'),
        props: { reportType: 'WEEKLY' },
        meta: { roles: STAFF_ROLES, title: '周报管理' },
      },
      {
        path: 'reports/weekly/review',
        name: 'admin-weekly-report-review',
        component: () => import('@/views/admin/ReportReviewView.vue'),
        props: { reportType: 'WEEKLY' },
        meta: { roles: STAFF_ROLES, title: '周报批阅' },
      },
      {
        path: 'points',
        name: 'admin-points',
        component: () => import('@/views/admin/PointLedgerView.vue'),
        meta: { roles: STAFF_ROLES, title: '积分流水' },
      },
      {
        path: 'audits',
        name: 'admin-audits',
        component: () => import('@/views/admin/AuditListView.vue'),
        meta: { roles: ['COLLEGE_ADMIN'], title: '审计记录' },
      },
    ],
  },

  // -- 兜底 ------------------------------------------------------------------
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]
