import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  QUIZ_IMPORT_HEADERS,
  buildQuizTemplateAoA,
  parseQuizImportRows,
} from '@/utils/quizImport'

const mocks = vi.hoisted(() => ({
  createQuizQuestion: vi.fn<(payload: unknown, key: string) => Promise<unknown>>(),
}))

vi.mock('@/api/modules/quiz', () => ({
  createQuizQuestion: mocks.createQuizQuestion,
}))

// 对话框级用例聚焦交互，解析细节由纯函数用例覆盖。
vi.mock('@/utils/quizImport', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/quizImport')>()
  return {
    ...actual,
    readQuizImportFile: vi.fn<(file: File) => Promise<QuizImportRow[]>>(),
  }
})

import QuizQuestionImportDialog from '@/components/quiz/QuizQuestionImportDialog.vue'
import { readQuizImportFile } from '@/utils/quizImport'
import type { QuizImportRow } from '@/utils/quizImport'

describe('quizImport 解析', () => {
  it('模板首行恰为 11 列表头', () => {
    expect(buildQuizTemplateAoA()[0]).toEqual([...QUIZ_IMPORT_HEADERS])
    expect(QUIZ_IMPORT_HEADERS).toEqual([
      '题型', '题干', '选项A', '选项B', '选项C', '选项D', '选项E', '选项F', '答案', '解析', '标签',
    ])
  })

  it('单选行映射为选项数组与字母答案，标签按顿号拆分', () => {
    const rows = parseQuizImportRows([
      QUIZ_IMPORT_HEADERS,
      ['单选', '第 k 层最多结点？', '2k-1', '2k', '2^(k-1)', 'k^2', '', '', 'C', '满二叉树性质', '树、二叉树'],
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0]!.error).toBeNull()
    expect(rows[0]!.payload).toEqual({
      type: 'SINGLE_CHOICE',
      stem: '第 k 层最多结点？',
      options: ['2k-1', '2k', '2^(k-1)', 'k^2'],
      answer: 'C',
      analysis: '满二叉树性质',
      tags: ['树', '二叉树'],
    })
  })

  it('多选答案 ABD 拆为字母数组，判断 对/错 转布尔，填空按竖线拆备选', () => {
    const rows = parseQuizImportRows([
      QUIZ_IMPORT_HEADERS,
      ['多选', '哪些是无损编码？', '哈夫曼', 'PCM', '行程', 'DPCM', '', '', 'AC', '', ''],
      ['判断', '空串即空白串。', '', '', '', '', '', '', '错', '', ''],
      ['填空', 'AVL 是____树。', '', '', '', '', '', '', '自平衡二叉搜索| 自平衡 ', '', ''],
    ])
    expect(rows[0]!.payload).toMatchObject({ type: 'MULTI_CHOICE', answer: ['A', 'C'] })
    expect(rows[1]!.payload).toMatchObject({ type: 'TRUE_FALSE', answer: false, options: null })
    expect(rows[2]!.payload).toMatchObject({
      type: 'FILL_BLANK',
      answer: ['自平衡二叉搜索', '自平衡'],
      options: null,
    })
  })

  it('未知题型、越界答案、选项不足分别标错；空行跳过', () => {
    const rows = parseQuizImportRows([
      QUIZ_IMPORT_HEADERS,
      ['简答', '写一篇', '', '', '', '', '', '', '', '', ''],
      ['单选', '答案越界', '甲', '乙', '', '', '', '', 'D', '', ''],
      ['单选', '选项不足', '甲', '', '', '', '', '', 'A', '', ''],
      ['', '', '', '', '', '', '', '', '', '', ''],
      ['判断', '缺答案', '', '', '', '', '', '', '', '', ''],
    ])
    expect(rows.map((r) => r.line)).toEqual([2, 3, 4, 6])
    expect(rows[0]!.error).toContain('题型')
    expect(rows[1]!.error).toContain('答案')
    expect(rows[2]!.error).toContain('选项')
    expect(rows[3]!.error).toContain('答案')
    expect(rows.every((r) => r.payload === null)).toBe(true)
  })
})

describe('QuizQuestionImportDialog 交互', () => {
  beforeEach(() => {
    mocks.createQuizQuestion.mockReset()
  })

  function makeRow(overrides: Partial<QuizImportRow>): QuizImportRow {
    return { line: 2, error: null, payload: { type: 'TRUE_FALSE', stem: 's', answer: true }, ...overrides } as QuizImportRow
  }

  it('解析后预览，逐行导入并汇总成功与失败，结束后通知刷新', async () => {
    const okRow = makeRow({})
    const badRow = makeRow({ line: 3, error: '答案缺失', payload: null })
    vi.mocked(readQuizImportFile).mockResolvedValue([okRow, badRow] as never)
    mocks.createQuizQuestion.mockResolvedValue({})

    const wrapper = mount(QuizQuestionImportDialog, {
      props: { modelValue: true },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const input = wrapper.get('input[type="file"]')
    const file = new File(['x'], 'quiz.xlsx')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('答案缺失')

    await wrapper.get('[data-test="start-import"]').trigger('click')
    await flushPromises()

    expect(mocks.createQuizQuestion).toHaveBeenCalledTimes(1)
    expect(mocks.createQuizQuestion).toHaveBeenCalledWith(okRow.payload, expect.any(String))
    expect(wrapper.text()).toContain('成功 1')
    expect(wrapper.text()).toContain('失败 1')
    expect(wrapper.emitted('imported')).toBeTruthy()
  })
})
