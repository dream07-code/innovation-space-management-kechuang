/**
 * 导航菜单。
 *
 * 依据需求文档 §10 的页面清单与契约 §5 的权限矩阵。
 * 注意两处容易弄错的边界：
 *   - 学院管理员账号**没有成员身份**，因此没有个人首页、个人积分与个人排名；
 *   - 方向负责人既是被管理的成员（可提交本人报告、有自己的积分），
 *     又有管理权限（可管理所辖方向的人员与报告），所以两组菜单都要有。
 */
import type { Role } from '@/api/types'

/** 图标键，由布局组件映射到具体的 Element Plus 图标组件。 */
export type MenuIconKey =
  | 'home'
  | 'document'
  | 'calendar'
  | 'trophy'
  | 'medal'
  | 'user'
  | 'users'
  | 'star'
  | 'setting'
  | 'list'
  | 'audit'
  | 'chat'

export interface MenuItem {
  label: string
  path: string
  icon: MenuIconKey
}

export interface MenuGroup {
  title: string
  items: MenuItem[]
}

const OJ_MEMBER_GROUP: MenuGroup = {
  title: 'OJ 考核',
  items: [
    { label: '考核列表', path: '/oj/assessments', icon: 'medal' },
    { label: '题库', path: '/oj/problems', icon: 'list' },
    { label: '提交记录', path: '/oj/submissions', icon: 'document' },
  ],
}

/** AI 助教对所有成员开放：不做题也能问知识点。 */
const TUTOR_GROUP: MenuGroup = {
  title: 'AI',
  items: [{ label: 'AI 助教', path: '/tutor', icon: 'chat' }],
}

const OJ_STAFF_GROUP: MenuGroup = {
  title: 'OJ 管理',
  items: [
    { label: '考核管理', path: '/oj/assessments', icon: 'medal' },
    { label: '题库管理', path: '/oj/problems', icon: 'list' },
    { label: '客观题库', path: '/oj/quiz-bank', icon: 'list' },
    { label: '提交记录', path: '/oj/submissions', icon: 'document' },
  ],
}

const MEMBER_GROUPS: MenuGroup[] = [
  {
    title: '我的',
    items: [
      { label: '个人首页', path: '/', icon: 'home' },
      { label: '日报', path: '/reports/daily', icon: 'document' },
      { label: '周报', path: '/reports/weekly', icon: 'calendar' },
      { label: '积分记录', path: '/points', icon: 'medal' },
      { label: '个人资料', path: '/profile', icon: 'user' },
    ],
  },
]

/** 负责人与管理员共用的管理菜单（负责人受所辖方向限制，由后端判定）。 */
const STAFF_GROUPS: MenuGroup[] = [
  {
    title: '人员与报告',
    items: [
      { label: '人员管理', path: '/admin/members', icon: 'users' },
      { label: '日报管理', path: '/admin/reports/daily', icon: 'document' },
      { label: '周报管理', path: '/admin/reports/weekly', icon: 'calendar' },
      { label: '积分流水', path: '/admin/points', icon: 'medal' },
    ],
  },
  {
    title: '公告',
    items: [{ label: '公告管理', path: '/admin/announcements', icon: 'document' }],
  },
]

/** 仅学院管理员可见。 */
const ADMIN_ONLY_GROUPS: MenuGroup[] = [
  {
    title: '管理',
    items: [
      { label: '负责人管理', path: '/admin/leaders', icon: 'users' },
      { label: '审计记录', path: '/admin/audits', icon: 'audit' },
    ],
  },
]

/** 排行榜对所有已登录角色开放（展示字段由后端裁剪，见契约 §5）。 */
const RANKING_GROUP: MenuGroup = {
  title: '排行',
  items: [{ label: '排行榜', path: '/rankings', icon: 'trophy' }],
}

/**
 * 按角色生成菜单。
 *
 * 管理员有管理首页而没有个人首页，因此单独插入一项。
 */
export function menuForRole(role: Role | null): MenuGroup[] {
  if (role === null) return []

  if (role === 'COLLEGE_ADMIN') {
    return [
      {
        title: '总览',
        items: [{ label: '管理首页', path: '/admin', icon: 'home' }],
      },
      ...STAFF_GROUPS,
      ...ADMIN_ONLY_GROUPS,
      OJ_STAFF_GROUP,
      RANKING_GROUP,
    ]
  }

  if (role === 'DIRECTION_LEADER') {
    return [...MEMBER_GROUPS, TUTOR_GROUP, OJ_STAFF_GROUP, ...STAFF_GROUPS, RANKING_GROUP]
  }

  return [...MEMBER_GROUPS, TUTOR_GROUP, OJ_MEMBER_GROUP, RANKING_GROUP]
}

/** 角色中文名，用于界面展示。 */
export const ROLE_LABELS: Record<Role, string> = {
  COLLEGE_ADMIN: '学院管理员',
  DIRECTION_LEADER: '方向负责人',
  STUDENT: '学生',
}
