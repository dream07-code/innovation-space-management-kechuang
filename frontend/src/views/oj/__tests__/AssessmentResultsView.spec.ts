import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AssessmentResultsView from '@/views/oj/AssessmentResultsView.vue'

const auth = vi.hoisted(() => ({ isAdmin: true, isLeader: false, isStudent: false }))
const push = vi.hoisted(() => vi.fn<(route: unknown) => void>())

const mocks = vi.hoisted(() => ({
  getAssessment: vi.fn<(id: string) => Promise<unknown>>(),
  listAssessmentProblem: vi.fn<() => Promise<unknown>>(),
  listAssessmentResults: vi.fn<(id: string, query?: unknown) => Promise<unknown>>(),
  listAssessmentMemberScores: vi.fn<(id: string, query?: unknown) => Promise<unknown>>(),
  listAnswerSheets: vi.fn<(id: string, query?: unknown) => Promise<unknown>>(),
  getMyAnswerSheet: vi.fn<(id: string) => Promise<unknown>>(),
  getQuizReviewSummary: vi.fn<(id: string) => Promise<unknown>>(),
  publishAssessmentResults: vi.fn<(id: string, payload: unknown, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/oj', () => ({
  getAssessment: mocks.getAssessment,
  listAssessmentProblems: mocks.listAssessmentProblem,
  listAssessmentResults: mocks.listAssessmentResults,
  listAssessmentMemberScores: mocks.listAssessmentMemberScores,
  publishAssessmentResults: mocks.publishAssessmentResults,
}))
vi.mock('@/api/modules/quiz', () => ({
  listAnswerSheets: mocks.listAnswerSheets,
  getMyAnswerSheet: mocks.getMyAnswerSheet,
  getQuizReviewSummary: mocks.getQuizReviewSummary,
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => auth,
}))
/** 模拟 vue-router 的 route.query：reactive 让组件内 watch 能响应「导航」。 */
const routeQuery = reactive<Record<string, unknown>>({})

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { assessmentId: '9001' },
    fullPath: '/oj/assessments/9001/results',
    query: routeQuery,
  }),
  useRouter: () => ({ push }),
}))

const failedResult = {
  assessmentId: '9001',
  submissionId: '9201',
  problemId: '9101',
  memberId: '201',
  memberName: '郑永康',
  memberStudentNo: '202502110229',
  technicalVerdict: 'ACCEPTED',
  passedTestCases: 7,
  totalTestCases: 7,
  timeMs: 146,
  memoryKb: 32768,
  autoScore: 100,
  humanScore: null,
  finalScore: 100,
  scoreSource: 'AUTO',
  resultStatus: 'COMPLETE',
  publishedAt: '2026-10-23T15:46:38+08:00',
}

const memberScore = {
  memberId: '201',
  memberName: '郑永康',
  memberStudentNo: '202502110229',
  itemCount: 4,
  answeredCount: 4,
  completedCount: 4,
  pendingCount: 0,
  totalScore: 48,
  maxScore: 60,
  scoreRate: 80,
  lastSubmittedAt: '2026-10-23T15:46:38+08:00',
}

/** 深度 watch 让组件持续订阅 routeQuery，用例结束必须卸载，避免旧实例串扰。 */
const mounted: VueWrapper[] = []
function trackMount(...args: Parameters<typeof mount>): VueWrapper {
  const wrapper = mount(...args)
  mounted.push(wrapper)
  return wrapper
}

/** 模拟路由导航到 query：组件内 watch 会驱动视图切换与取数。 */
async function navigateQuery(query: Record<string, string>): Promise<void> {
  Object.keys(routeQuery).forEach((key) => delete routeQuery[key])
  Object.assign(routeQuery, query)
  await flushPromises()
}

/** 默认进入「按学生汇总」，看提交明细的用例先切到「按提交」。 */
async function switchToSubmission(): Promise<void> {
  await navigateQuery({ view: 'submission' })
}

describe('AssessmentResultsView 评分来源与人工评分入口', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop()!.unmount()
  })

  beforeEach(() => {
    Object.keys(routeQuery).forEach((key) => delete routeQuery[key])
    auth.isAdmin = true
    auth.isLeader = false
    auth.isStudent = false
    push.mockReset()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mocks.publishAssessmentResults.mockReset()
    mocks.publishAssessmentResults.mockResolvedValue({
      publishedAt: '2026-11-04T12:00:00+08:00',
      publisher: '1',
      settled: true,
    })
    mocks.getQuizReviewSummary.mockReset()
    mocks.getQuizReviewSummary.mockResolvedValue({
      totalItems: 4,
      gradedItems: 4,
      pendingItems: 0,
      submittedSheets: 2,
      resultsPublished: false,
      canPublish: true,
      pointsEnabled: true,
    })
    mocks.listAssessmentResults.mockReset()
    mocks.listAssessmentMemberScores.mockReset()
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'PUBLISHED',
    })
    mocks.listAssessmentProblem.mockResolvedValue({ items: [{ id: '9101', title: '矩阵快速幂求斐波那契' }] })
    mocks.listAssessmentResults.mockResolvedValue({
      items: [failedResult],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.listAssessmentMemberScores.mockResolvedValue({
      items: [memberScore],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.listAnswerSheets.mockReset()
    mocks.getMyAnswerSheet.mockReset()
  })

  it('默认按学生汇总，显示分值口径总分、得分率与完成条目数', async () => {
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(mocks.listAssessmentMemberScores).toHaveBeenCalledWith('9001', { page: 1, size: 20 })
    expect(wrapper.text()).toContain('按学生汇总')
    expect(wrapper.text()).toContain('4 / 4')
    expect(wrapper.text()).toContain('48')
    expect(wrapper.text()).toContain('60')
    expect(wrapper.text()).toContain('得分率')
    expect(wrapper.text()).toContain('80%')
    expect(wrapper.text()).not.toContain('平均分')
    expect(wrapper.text()).not.toContain('AI 评分')
  })

  it('工作人员可下钻某学生的答题卡逐题对错与解析', async () => {
    mocks.listAnswerSheets.mockResolvedValue({
      items: [
        {
          memberId: '201',
          memberName: '郑永康',
          status: 'SUBMITTED',
          answeredCount: 2,
          totalScore: 8,
          submittedAt: '2026-10-23T15:46:38+08:00',
          items: [
            { position: 1, answer: 'A', correct: true, awarded: 5, correctAnswer: 'A', analysis: '满二叉树性质' },
            { position: 2, answer: ['A', 'C'], correct: false, awarded: 0, correctAnswer: ['A', 'B'], analysis: 'DPCM 是有损编码' },
          ],
        },
      ],
      page: 1,
      size: 1,
      total: 1,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="sheet-201"]').trigger('click')
    await flushPromises()

    expect(mocks.listAnswerSheets).toHaveBeenCalledWith('9001', expect.objectContaining({ memberId: '201' }))
    expect(wrapper.text()).toContain('答题卡')
    expect(wrapper.text()).toContain('满二叉树性质')
    expect(wrapper.text()).toContain('DPCM 是有损编码')
  })

  it('学生没有答题卡下钻入口', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="sheet-201"]').exists()).toBe(false)
  })

  it('点明细产生携带视图状态的下钻导航，返回时恢复汇总视图', async () => {
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const detail = wrapper.findAll('button').find((item) => item.text().includes('明细'))
    expect(detail).toBeTruthy()
    await detail!.trigger('click')
    await flushPromises()

    // 下钻是真实导航：query 携带 view/member，浏览器返回键因此能回到汇总。
    expect(push).toHaveBeenCalledWith({
      query: { view: 'submission', member: '201', memberName: '郑永康' },
    })

    // 模拟用户点浏览器返回（query 回到无参）：应切回按学生汇总。
    await navigateQuery({})
    expect(mocks.listAssessmentMemberScores).toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('只看 郑永康')
  })

  it('按通过的测试点显示自动分与计分规则，不再出现 AI 评分列', async () => {
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await switchToSubmission()

    expect(wrapper.text()).toContain('自动分')
    expect(wrapper.text()).toContain('100 分')
    expect(wrapper.text()).toContain('按测试点')
    expect(wrapper.text()).toContain('自动分按通过的测试点比例计算')
    expect(wrapper.text()).not.toContain('AI 评分')
  })

  it('没有自动分时提示需要人工评分', async () => {
    mocks.listAssessmentResults.mockResolvedValue({
      items: [
        {
          ...failedResult,
          autoScore: null,
          finalScore: null,
          scoreSource: 'PENDING',
          resultStatus: 'NEEDS_REVIEW',
          technicalVerdict: 'INTERNAL_ERROR',
        },
      ],
      page: 1,
      size: 20,
      total: 1,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await switchToSubmission()

    expect(wrapper.text()).toContain('有 1 条提交没有自动分')
    expect(wrapper.text()).toContain('需要人工评分')
  })

  it('管理员可以从结果页直接进入人工评分并带回跳地址', async () => {
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await switchToSubmission()

    const reviewButton = wrapper.findAll('button').find((item) => item.text().includes('评分'))
    expect(reviewButton).toBeTruthy()
    await reviewButton!.trigger('click')

    // redirect 携带当前视图状态，评分完成才能回到「按提交」而不是回落汇总。
    expect(push).toHaveBeenCalledWith(
      '/oj/submissions/9201/review?redirect=%2Foj%2Fassessments%2F9001%2Fresults%3Fview%3Dsubmission',
    )
  })

  it('评分返回时通过 query 恢复按提交视图与成员过滤', async () => {
    Object.assign(routeQuery, { view: 'submission', member: '201', memberName: '冀玉成' })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(mocks.listAssessmentResults).toHaveBeenCalledWith('9001', {
      page: 1,
      size: 20,
      memberId: '201',
    })
    expect(wrapper.text()).toContain('只看 冀玉成')
    // 不应请求按学生汇总。
    expect(mocks.listAssessmentMemberScores).not.toHaveBeenCalled()
  })

  it('学生看不到人工评分入口', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await switchToSubmission()

    const reviewButton = wrapper.findAll('button').find((item) => item.text().includes('评分'))
    expect(reviewButton).toBeUndefined()
  })
})


describe('AssessmentResultsView 成绩发布', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop()!.unmount()
  })

  beforeEach(() => {
    while (mounted.length) mounted.pop()!.unmount()
    Object.keys(routeQuery).forEach((key) => delete routeQuery[key])
    auth.isAdmin = true
    auth.isLeader = false
    auth.isStudent = false
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 7,
      resultsPublished: false,
    })
    mocks.listAssessmentProblem.mockResolvedValue({ items: [] })
    mocks.listAssessmentResults.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    mocks.listAssessmentMemberScores.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    mocks.publishAssessmentResults.mockReset()
    mocks.publishAssessmentResults.mockResolvedValue({
      publishedAt: '2026-11-04T12:00:00+08:00',
      publisher: '1',
      settled: true,
    })
    mocks.getQuizReviewSummary.mockReset()
    mocks.getQuizReviewSummary.mockResolvedValue({
      totalItems: 4,
      gradedItems: 4,
      pendingItems: 0,
      submittedSheets: 2,
      resultsPublished: false,
      canPublish: true,
      pointsEnabled: true,
    })
  })

  it('还有未批条目时发布按钮禁用并显示剩余数', async () => {
    auth.isAdmin = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 3,
      resultsPublished: false,
    })
    mocks.getQuizReviewSummary.mockResolvedValue({
      totalItems: 4,
      gradedItems: 1,
      pendingItems: 3,
      submittedSheets: 2,
      resultsPublished: false,
      canPublish: false,
      pointsEnabled: true,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const button = wrapper.get('[data-test="publish"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toContain('还有 3 条未批')
  })

  it('阅卷完成后确认发布，调用发布接口并带当前版本', async () => {
    auth.isAdmin = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 7,
      resultsPublished: false,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await flushPromises()
    expect(wrapper.get('[data-test="publish"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="publish"]').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(mocks.publishAssessmentResults).toHaveBeenCalledWith(
      '9001',
      { version: 7 },
      expect.any(String),
    )
  })

  it('学生未发布时看到成绩待公布', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    auth.isStudent = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 3,
      resultsPublished: false,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('成绩待公布')
    expect(wrapper.find('[data-test="publish"]').exists()).toBe(false)
  })

  it('学生未发布时不开放按提交明细，总分显示待公布', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    auth.isStudent = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 3,
      resultsPublished: false,
    })
    mocks.listAssessmentMemberScores.mockResolvedValue({
      items: [{ ...memberScore, totalScore: null, scoreRate: null, sheetScore: null }],
      page: 1,
      size: 20,
      total: 1,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    // 未发布时学生拿不到分数：不提供「按提交」入口，也不显示得分率。
    expect(wrapper.text()).not.toContain('按提交')
    expect(wrapper.findAll('button').some((item) => item.text().includes('明细'))).toBe(false)
    expect(wrapper.text()).toContain('待公布')
    expect(wrapper.text()).not.toContain('80%')
  })

  it('学生可打开自己的答题卡并看到逐题对错、评语与解析', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    auth.isStudent = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 4,
      resultsPublished: true,
    })
    mocks.listAssessmentMemberScores.mockResolvedValue({
      items: [memberScore],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.getMyAnswerSheet.mockReset()
    mocks.getMyAnswerSheet.mockResolvedValue({
      status: 'SUBMITTED',
      resultsPublished: true,
      answeredCount: 2,
      totalScore: 5,
      submittedAt: '2026-11-04T12:00:00+08:00',
      items: [
        { position: 1, answer: 'A', correct: true, awarded: 5, correctAnswer: 'A', analysis: '满二叉树性质', reviewComment: '答得好' },
        { position: 2, answer: 'B', correct: false, awarded: 0, correctAnswer: 'A', analysis: 'DPCM 是有损编码', reviewComment: '再看一遍编码定义' },
      ],
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="my-sheet"]').trigger('click')
    await flushPromises()

    expect(mocks.getMyAnswerSheet).toHaveBeenCalledWith('9001')
    const dialog = wrapper.get('[data-test="my-sheet-dialog"]')
    expect(dialog.text()).toContain('满二叉树性质')
    expect(dialog.text()).toContain('再看一遍编码定义')
    expect(dialog.text()).toContain('客观题得分')
    // 学生视图不暴露内部评分字段。
    expect(dialog.text()).not.toContain('自动分')
    expect(dialog.text()).not.toContain('人工分')
  })

  it('学生未发布时我的答题卡只提示待公布', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    auth.isStudent = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 3,
      resultsPublished: false,
    })
    mocks.listAssessmentMemberScores.mockResolvedValue({
      items: [{ ...memberScore, totalScore: null, scoreRate: null }],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.getMyAnswerSheet.mockReset()
    mocks.getMyAnswerSheet.mockResolvedValue({
      status: 'SUBMITTED',
      resultsPublished: false,
      answeredCount: 2,
      totalScore: null,
      submittedAt: null,
      items: null,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-test="my-sheet"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('成绩尚未公布')
  })

  it('学生未参加时显示未参加而不是 0 分', async () => {
    auth.isAdmin = false
    auth.isLeader = false
    auth.isStudent = true
    mocks.getAssessment.mockResolvedValue({
      id: '9001',
      title: '正式考核',
      status: 'CLOSED',
      version: 3,
      resultsPublished: true,
    })
    mocks.listAssessmentMemberScores.mockResolvedValue({
      items: [
        {
          memberId: '201',
          memberName: '郑永康',
          memberStudentNo: '202502110229',
          itemCount: 2,
          answeredCount: 0,
          completedCount: 0,
          pendingCount: 0,
          totalScore: 0,
          maxScore: 105,
          scoreRate: 0,
          sheetStatus: 'NONE',
          lastSubmittedAt: null,
        },
      ],
      page: 1,
      size: 20,
      total: 1,
    })
    const wrapper = trackMount(AssessmentResultsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="results-absent"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('未参加')
    // 不能把「没参加」渲染成 0 分。
    expect(wrapper.text()).not.toContain('0 / 105')
    expect(wrapper.text()).not.toContain('0%')
  })
})
