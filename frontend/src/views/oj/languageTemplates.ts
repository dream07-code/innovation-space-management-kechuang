/**
 * OJ 各语言起始代码框架。
 *
 * 目标：成员选中语言后直接拿到可编译、可运行的完整骨架（头文件、输入读取、输出缓冲），
 * 只需要在标记处补充算法，避免手写 include/import 或 fast-IO 出错。
 *
 * 判题侧语言版本来自 Judge0 语言 ID（见后端 Judge0OjAdapter）：
 * C++ 54 / Java 62 / Python 71 / JavaScript 63 / Go 60 / Rust 73。
 * 因此模板只使用各版本都支持的标准语法，不使用 nightly 或新标准特性。
 */
import type { AssessmentLanguage } from '@/api/types'

export interface LanguageRuntime {
  /** 判题机语言版本说明，展示在编辑器工具栏。 */
  runtime: string
  /** 提交形态约束，例如 Java 主类名固定为 Main。 */
  submitHint: string
  /** 默认缩进空格数。 */
  indentSize: number
  /** 起始代码框架。 */
  starter: string
}

const CPP_STARTER = `#include <bits/stdc++.h>
using namespace std;

int main() {
    // 关闭 C 与 C++ 标准流的同步，加快大量输入时的读取速度
    ios::sync_with_stdio(false);
    cin.tie(nullptr);

    // TODO: 在此读取输入并输出答案
    // 例如：int n; cin >> n;

    return 0;
}
`

const JAVA_STARTER = `import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.StringTokenizer;

public class Main {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder out = new StringBuilder();

        // TODO: 在此读取输入并输出答案
        // 例如：StringTokenizer st = new StringTokenizer(in.readLine());

        System.out.print(out);
    }
}
`

const PYTHON_STARTER = `import sys


def solve() -> None:
    data = sys.stdin.buffer.read().split()

    # TODO: 在此读取输入并输出答案
    # 例如：n = int(data[0])


if __name__ == "__main__":
    solve()
`

const JAVASCRIPT_STARTER = `const fs = require('fs')

const data = fs.readFileSync(0, 'utf8').trim().split(/\\s+/)

function solve() {
  // TODO: 在此读取输入并输出答案
  // 例如：const n = Number(data[0])
}

solve()
`

const GO_STARTER = `package main

import (
	"bufio"
	"os"
)

// 需要 fmt 时在 import 中追加 "fmt"，否则 Go 会因未使用的导入而编译失败。
func solve(in *bufio.Reader, out *bufio.Writer) {
	// TODO: 在此读取输入并输出答案
	// 例如：var n int; fmt.Fscan(in, &n); fmt.Fprintln(out, n)
}

func main() {
	in := bufio.NewReader(os.Stdin)
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()

	solve(in, out)
}
`

const RUST_STARTER = `use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).expect("read stdin");
    let tokens: Vec<&str> = input.split_whitespace().collect();

    // TODO: 在此处理 tokens 并输出答案
    // 例如：let n: usize = tokens[0].parse().unwrap();
    //      println!("{}", n);
}
`

export const LANGUAGE_RUNTIMES: Record<AssessmentLanguage, LanguageRuntime> = {
  cpp: {
    runtime: 'C++17 · GCC 9',
    submitHint: '单文件提交，可直接使用 bits/stdc++.h 与 STL；不要额外提交头文件。',
    indentSize: 4,
    starter: CPP_STARTER,
  },
  java: {
    runtime: 'Java 13 · OpenJDK',
    submitHint: '主类名必须是 Main，且不能写在 package 里；提交单个 .java 文件内容。',
    indentSize: 4,
    starter: JAVA_STARTER,
  },
  python: {
    runtime: 'Python 3.8',
    submitHint: '单文件提交，使用 sys.stdin 读取输入；不要在顶层写 input() 依赖交互。',
    indentSize: 4,
    starter: PYTHON_STARTER,
  },
  javascript: {
    runtime: 'Node.js 12',
    submitHint: '单文件提交，使用 CommonJS（require），不要使用 ESM import。',
    indentSize: 2,
    starter: JAVASCRIPT_STARTER,
  },
  go: {
    runtime: 'Go 1.13',
    submitHint: '必须是 package main 且包含 main()，未使用的 import 会导致编译失败。',
    indentSize: 4,
    starter: GO_STARTER,
  },
  rust: {
    runtime: 'Rust 1.40',
    submitHint: '单文件提交（crate 根），从标准输入读取全部数据后自行解析。',
    indentSize: 4,
    starter: RUST_STARTER,
  },
}

export function starterCode(language: AssessmentLanguage): string {
  return LANGUAGE_RUNTIMES[language]?.starter ?? ''
}

export function languageRuntime(language: AssessmentLanguage): LanguageRuntime | null {
  return LANGUAGE_RUNTIMES[language] ?? null
}
