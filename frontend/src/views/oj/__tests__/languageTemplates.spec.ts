import { describe, expect, it } from 'vitest'

import type { AssessmentLanguage } from '@/api/types'
import { LANGUAGE_RUNTIMES, languageRuntime, starterCode } from '@/views/oj/languageTemplates'

const LANGUAGES = Object.keys(LANGUAGE_RUNTIMES) as AssessmentLanguage[]

describe('OJ 起始代码框架', () => {
  it('覆盖后端允许的全部语言', () => {
    expect(new Set(LANGUAGES)).toEqual(
      new Set(['cpp', 'java', 'python', 'javascript', 'go', 'rust']),
    )
  })

  it('每种语言都提供可编译的骨架与提交约束说明', () => {
    for (const language of LANGUAGES) {
      const runtime = LANGUAGE_RUNTIMES[language]
      expect(runtime.starter.trim().length).toBeGreaterThan(20)
      expect(runtime.runtime).not.toBe('')
      expect(runtime.submitHint).not.toBe('')
      expect(runtime.indentSize).toBeGreaterThanOrEqual(2)
      expect(starterCode(language)).toBe(runtime.starter)
    }
  })

  it('C++ 预置头文件与快速输入输出，Java 固定 Main 主类', () => {
    expect(starterCode('cpp')).toContain('#include <bits/stdc++.h>')
    expect(starterCode('cpp')).toContain('ios::sync_with_stdio(false)')
    expect(starterCode('cpp')).toContain('int main()')
    expect(starterCode('java')).toContain('public class Main')
    expect(starterCode('java')).toMatch(/BufferedReader/)
    expect(starterCode('python')).toContain('import sys')
    expect(starterCode('go')).toContain('package main')
    expect(starterCode('rust')).toContain('fn main()')
  })

  it('未知语言回退为空字符串且不抛错', () => {
    expect(starterCode('kotlin' as AssessmentLanguage)).toBe('')
    expect(languageRuntime('kotlin' as AssessmentLanguage)).toBeNull()
  })
})
