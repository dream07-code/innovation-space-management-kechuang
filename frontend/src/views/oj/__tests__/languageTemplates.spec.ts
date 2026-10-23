import { describe, expect, it } from 'vitest'

import type { AssessmentLanguage } from '@/api/types'
import {
  LANGUAGE_RUNTIMES,
  functionStarter,
  languageRuntime,
  starterCode,
  starterForProblem,
} from '@/views/oj/languageTemplates'

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

describe('函数式判题起始代码', () => {
  const signature = { functionName: 'fib', parameterTypes: ['long'], returnType: 'long' }

  it('按语言生成函数骨架而不是完整程序', () => {
    expect(functionStarter('cpp', signature)).toContain('class Solution')
    expect(functionStarter('cpp', signature)).toContain('long long fib(long long arg0)')
    expect(functionStarter('cpp', signature)).not.toContain('int main')

    expect(functionStarter('java', signature)).toContain('public long fib(long arg0)')
    expect(functionStarter('java', signature)).not.toContain('public static void main')

    expect(functionStarter('python', signature)).toContain('def fib(self, arg0):')
    expect(functionStarter('javascript', signature)).toContain('fib(arg0)')
    expect(functionStarter('go', signature)).toContain('func fib(arg0 int64) int64')
    expect(functionStarter('rust', signature)).toContain('pub fn fib(arg0: i64) -> i64')
  })

  it('支持数组参数与数组返回值', () => {
    const arraySignature = {
      functionName: 'twoSum',
      parameterTypes: ['int[]', 'int'],
      returnType: 'int[]',
    }
    expect(functionStarter('java', arraySignature)).toContain('public int[] twoSum(int[] arg0, int arg1)')
    expect(functionStarter('cpp', arraySignature)).toContain('vector<int> twoSum(vector<int> arg0, int arg1)')
    expect(functionStarter('go', arraySignature)).toContain('func twoSum(arg0 []int, arg1 int) []int')
  })

  it('题目为命令行模式时回退到标准模板', () => {
    expect(
      starterForProblem('cpp', { judgeMode: 'STDIO' }),
    ).toContain('#include <bits/stdc++.h>')
    expect(
      starterForProblem('cpp', {
        judgeMode: 'FUNCTION',
        functionName: 'fib',
        parameterTypes: ['int'],
        returnType: 'int',
      }),
    ).toContain('int fib(int arg0)')
  })
})
