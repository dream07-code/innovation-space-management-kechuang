import { beforeEach, describe, expect, it, vi } from 'vitest'

import { get } from '@/api/client'
import type { CreateQuizQuestionRequest } from '@/api/types'

vi.mock('@/api/client', () => ({
  API_PREFIX: '/api/v1',
  get: vi.fn<(url: string, config?: unknown) => Promise<unknown>>(),
  patch: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
  post: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
  put: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
}))

const mockedGet = vi.mocked(get)

beforeEach(() => {
  mockedGet.mockReset()
  mockedGet.mockResolvedValue({} as never)
})

describe('客观题库 API', () => {
  it('分页列出客观题并透传过滤参数', async () => {
    const api = await import('@/api/modules/quiz')
    await api.listQuizQuestions({
      page: 1,
      size: 20,
      type: 'SINGLE_CHOICE',
      status: 'PUBLISHED',
      keyword: '二叉树',
    })

    expect(mockedGet).toHaveBeenCalledWith('/api/v1/quiz-questions', {
      params: {
        page: 1,
        size: 20,
        type: 'SINGLE_CHOICE',
        status: 'PUBLISHED',
        keyword: '二叉树',
      },
    })
  })

  it('以幂等键创建客观题', async () => {
    const api = await import('@/api/modules/quiz')
    const payload = {
      type: 'SINGLE_CHOICE',
      stem: '下列哪种遍历是层序遍历？',
      options: ['前序', '中序', '广度优先', '深度优先'],
      answer: 'C',
      tags: ['树'],
    }
    await api.createQuizQuestion(payload, 'create-key')

    expect(vi.mocked((await import('@/api/client')).post)).toHaveBeenCalledWith(
      '/api/v1/quiz-questions',
      payload,
      { idempotencyKey: 'create-key' },
    )
  })

  it('读取、更新与停用客观题', async () => {
    const api = await import('@/api/modules/quiz')
    await api.getQuizQuestion('9201')
    await api.updateQuizQuestion('9201', { stem: '新题干' })
    await api.retireQuizQuestion('9201', 'retire-key')

    const client = await import('@/api/client')
    expect(vi.mocked(client.get)).toHaveBeenCalledWith('/api/v1/quiz-questions/9201')
    expect(vi.mocked(client.patch)).toHaveBeenCalledWith('/api/v1/quiz-questions/9201', {
      stem: '新题干',
    })
    expect(vi.mocked(client.post)).toHaveBeenCalledWith(
      '/api/v1/quiz-questions/9201/retire',
      {},
      { idempotencyKey: 'retire-key' },
    )
  })
})

describe('试卷与答题卡 API', () => {
  it('读取学生试卷', async () => {
    const api = await import('@/api/modules/quiz')
    await api.getAssessmentPaper('9001')

    expect(mockedGet).toHaveBeenCalledWith('/api/v1/assessments/9001/paper')
  })

  it('PUT 整体覆盖草稿，交卷带幂等键', async () => {
    const api = await import('@/api/modules/quiz')
    const answers = { '1': 'A', '2': ['A', 'B'] }
    await api.saveAnswerDraft('9001', answers)
    await api.submitAnswerSheet('9001', 'submit-key')

    const client = await import('@/api/client')
    expect(vi.mocked(client.put)).toHaveBeenCalledWith(
      '/api/v1/assessments/9001/answer-sheet',
      { answers },
    )
    expect(vi.mocked(client.post)).toHaveBeenCalledWith(
      '/api/v1/assessments/9001/answer-sheet/submit',
      {},
      { idempotencyKey: 'submit-key' },
    )
  })

  it('读取本人答题卡并分页列出逐生答题卡', async () => {
    const api = await import('@/api/modules/quiz')
    await api.getMyAnswerSheet('9001')
    await api.listAnswerSheets('9001', { page: 1, size: 20, memberId: '3001' })

    expect(mockedGet).toHaveBeenCalledWith('/api/v1/assessments/9001/my-answer-sheet')
    expect(mockedGet).toHaveBeenCalledWith('/api/v1/assessments/9001/answer-sheets', {
      params: { page: 1, size: 20, memberId: '3001' },
    })
  })
})
