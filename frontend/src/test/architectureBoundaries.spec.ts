// 文件名:architectureBoundaries.spec.ts
// 用途:前端结构边界的守护测试:用 TypeScript 解析器逐语句分类导入,断言 src 模块间无运行时 import 环、shared/ 绝不引用 features/ 或 app/(含类型引用)。
/*
 * 针对真实源码树断言的前端结构边界。
 *
 * 后端有 ArchUnit 门禁,前端此前没有——散乱的目录结构正是因此出现的。
 * 这里强制两条规则:
 *
 *  1. src 模块之间没有运行时 import 环。只有模块被实际加载时一条边才是
 *     "运行时"边——`import type` 与 `export type ... from` 被整体擦除,
 *     因此不可能成环;其余形式(包括编译器保留为 `import {} from '...'`
 *     的 `import { type T }`)都会加载。
 *  2. `shared/` 绝不伸入 `features/` 或 `app/`。这条规则把纯类型引用也算
 *     进去:让 shared 依赖一个业务类型,会把最底层重新拖回依赖图上方,
 *     是同一种耦合换了件更便宜的外衣。
 *
 * 分类按语句用 TypeScript 解析器完成,而不是按说明符字符串:
 * `import type { A } from './m'` 之后跟 `import { b } from './m'`,是一条
 * 被擦除的导入加一条真实运行时导入,按字符串维护的"仅类型"集合会错误地
 * 丢掉后者。什么算被擦除,由编译器自己使用的同一个标志决定,绝不看绑定
 * 列表的形状——见下方 `importIsErased` 的产出形式表。
 *
 * 测试文件不参与依赖图:它们按设计导入生产模块,永远不会进入发布包。
 */
import { describe, expect, it } from 'vitest'
import ts from 'typescript'

type Graph = Map<string, Set<string>>

const SUFFIXES = ['', '.ts', '.vue', '/index.ts']

const sources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>

/** `../features/workspace/WorkspaceView.vue` → `src/features/workspace/WorkspaceView.vue` */
function moduleOf(globKey: string): string {
  return `src/${globKey.replace(/^\.\.\//, '')}`
}

const modules = new Map<string, string>()
for (const [key, text] of Object.entries(sources)) {
  modules.set(moduleOf(key), text)
}

const known = new Set(modules.keys())
const shipped = [...known]
  .filter((path) => !path.includes('__tests__') && !path.endsWith('.spec.ts'))
  .sort()

/** 单文件组件的 script 段;`.ts` 文件则是文件本身。 */
function scriptOf(path: string, text: string): string {
  if (!path.endsWith('.vue')) return text
  const blocks = [...text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1])
  return blocks.join('\n')
}

/** 把说明符解析到被追踪的模块;外部依赖/CSS 返回 null。 */
function resolve(importer: string, specifier: string): string | null {
  const spec = specifier.split('?')[0]
  const raw = spec.startsWith('@/')
    ? `src/${spec.slice(2)}`
    : spec.startsWith('.')
      ? new URL(spec, `file:///${importer}`).pathname.slice(1)
      : null
  if (raw === null) return null

  for (const candidate of [raw, ...SUFFIXES.map((suffix) => raw + suffix)]) {
    const parts: string[] = []
    for (const segment of candidate.split('/')) {
      if (segment === '.' || segment === '') continue
      if (segment === '..') parts.pop()
      else parts.push(segment)
    }
    const normalised = parts.join('/')
    if (known.has(normalised)) return normalised
  }
  return null
}

interface References {
  /** 编译后仍然存在的语句。 */
  runtime: string[]
  /** 编译器移除的语句。 */
  erased: string[]
}

/*
 * 编译器是否把模块说明符完全丢弃——这是 import 边在运行时不存在的唯一
 * 条件。
 *
 * 朴素的两种规则都是错的。下面的每种形式都用本仓库自己的编译器设置
 * (`verbatimModuleSyntax: true`)实际产出过,记录真实行为:
 *
 *   import type { T } from './m'        -> (无输出)                    erased
 *   import { type T } from './m'        -> import {} from './m'        runtime
 *   import value, { type T } from './m' -> import value, {} from './m' runtime
 *   import * as ns from './m'           -> import * as ns from './m'   runtime
 *   import './m'                        -> import './m'                runtime
 *
 * 所以"所有具名绑定都标了 type"并不会擦除该语句(模块仍被加载),而默认
 * 绑定永远是值。读取编译器自己使用的那个标志,就能消除这一整类错误。
 */
function importIsErased(node: ts.ImportDeclaration): boolean {
  const clause = node.importClause
  if (clause === undefined) return false // 裸的 `import './x'` 是真实副作用
  return clause.isTypeOnly
}

/*
 * re-export 的同一个问题。已验证的产出:
 *
 *   export type { T } from './m' -> export {};               erased
 *   export { type T } from './m' -> export {} from './m';    runtime
 */
function exportIsErased(node: ts.ExportDeclaration): boolean {
  return node.isTypeOnly
}

function classify(importer: string, text: string): References {
  const source = ts.createSourceFile(
    importer,
    scriptOf(importer, text),
    ts.ScriptTarget.ESNext,
    /* setParentNodes */ true,
    ts.ScriptKind.TS,
  )
  const runtime: string[] = []
  const erased: string[] = []

  for (const statement of source.statements) {
    if (ts.isImportDeclaration(statement)) {
      if (!ts.isStringLiteral(statement.moduleSpecifier)) continue
      const specifier = statement.moduleSpecifier.text
      ;(importIsErased(statement) ? erased : runtime).push(specifier)
      continue
    }
    if (ts.isExportDeclaration(statement)) {
      if (statement.moduleSpecifier === undefined || !ts.isStringLiteral(statement.moduleSpecifier)) continue
      const specifier = statement.moduleSpecifier.text
      ;(exportIsErased(statement) ? erased : runtime).push(specifier)
    }
  }

  // `import('x')` 可能出现在任何位置:运行时调用,或类型位置的查询。
  const visit = (node: ts.Node): void => {
    if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
      const argument = node.arguments[0]
      if (argument !== undefined && ts.isStringLiteral(argument)) runtime.push(argument.text)
    } else if (
      ts.isImportTypeNode(node) &&
      ts.isLiteralTypeNode(node.argument) &&
      ts.isStringLiteral(node.argument.literal)
    ) {
      erased.push(node.argument.literal.text)
    }
    ts.forEachChild(node, visit)
  }
  visit(source)

  return { runtime, erased }
}

function addEdge(graph: Graph, from: string, to: string): void {
  const outgoing = graph.get(from) ?? new Set<string>()
  outgoing.add(to)
  graph.set(from, outgoing)
}

const runtimeGraph: Graph = new Map()
/** 运行时加上被擦除的引用:分层规则两者都关心。 */
const referenceGraph: Graph = new Map()

for (const importer of shipped) {
  runtimeGraph.set(importer, new Set<string>())
  referenceGraph.set(importer, new Set<string>())
  const { runtime, erased } = classify(importer, modules.get(importer) ?? '')

  for (const specifier of runtime) {
    const target = resolve(importer, specifier)
    if (target === null || target === importer) continue
    addEdge(referenceGraph, importer, target)
    addEdge(runtimeGraph, importer, target)
  }
  for (const specifier of erased) {
    const target = resolve(importer, specifier)
    if (target === null || target === importer) continue
    addEdge(referenceGraph, importer, target)
  }
}

function findCycles(graph: Graph): string[] {
  const state = new Map<string, 'visiting' | 'done'>()
  const stack: string[] = []
  const cycles: string[] = []

  const visit = (node: string): void => {
    state.set(node, 'visiting')
    stack.push(node)
    for (const next of graph.get(node) ?? []) {
      if (state.get(next) === 'visiting') {
        cycles.push([...stack.slice(stack.indexOf(next)), next].join(' -> '))
      } else if (!state.has(next)) {
        visit(next)
      }
    }
    stack.pop()
    state.set(node, 'done')
  }

  for (const node of [...graph.keys()].sort()) {
    if (!state.has(node)) visit(node)
  }
  return cycles
}

const crossLayerEdges = [...referenceGraph.entries()]
  .filter(([from]) => from.startsWith('src/shared/'))
  .flatMap(([from, targets]) =>
    [...targets]
      .filter((to) => to.startsWith('src/features/') || to.startsWith('src/app/'))
      .map((to) => `${from} -> ${to}`),
  )

const runtimeEdgeCount = [...runtimeGraph.values()].reduce((total, set) => total + set.size, 0)
const erasedOnlyCount = [...referenceGraph.entries()].reduce(
  (total, [from, targets]) =>
    total + [...targets].filter((to) => !(runtimeGraph.get(from)?.has(to) ?? false)).length,
  0,
)

describe('frontend structure boundaries', () => {
  it('scans the real source tree, not an empty glob', () => {
    expect(shipped.length).toBeGreaterThan(120)
    expect(shipped.some((path) => path.startsWith('src/features/'))).toBe(true)
    expect(shipped.some((path) => path.startsWith('src/shared/'))).toBe(true)
    expect(shipped.some((path) => path.startsWith('src/app/'))).toBe(true)
    expect(runtimeGraph.size).toBe(shipped.length)
  })

  it('classifies erased imports per statement, not per specifier', () => {
    // 回归守护:此门禁曾有过的 bug——同一路径的一条被擦除导入与一条
    // 真实导入绝不能合并归类为"被擦除"。
    const probe = [
      "import type { OnlyType } from './probe-target'",
      "import { realValue } from './probe-target'",
    ].join('\n')

    const { runtime, erased } = classify('src/__classify_probe__.ts', probe)
    expect(erased).toEqual(['./probe-target'])
    expect(runtime).toEqual(['./probe-target'])
  })

  it('classifies edges the way the compiler actually emits them', () => {
    // 每条预期都用本仓库的 `verbatimModuleSyntax: true` 实际产出验证过。
    // 过去被误读的两种形状是默认绑定形式与全类型标记的具名形式。
    const cases: Array<[statement: string, expected: 'runtime' | 'erased']> = [
      ["import type { T } from './m'", 'erased'],
      ["import type D from './m'", 'erased'],
      ["import type * as ns from './m'", 'erased'],
      ["import { type T } from './m'", 'runtime'],
      ["import value, { type T } from './m'", 'runtime'],
      ["import value, { type T, type U } from './m'", 'runtime'],
      ["import value, { real } from './m'", 'runtime'],
      ["import * as ns from './m'", 'runtime'],
      ["import './m'", 'runtime'],
      ["export type { T } from './m'", 'erased'],
      ["export { type T } from './m'", 'runtime'],
      ["export { real } from './m'", 'runtime'],
      ["export * from './m'", 'runtime'],
    ]

    const mismatches = cases
      .map(([statement, expected]) => {
        const { runtime, erased } = classify('src/__classify_probe__.ts', statement)
        const sawRuntime = runtime.includes('./m')
        const sawErased = erased.includes('./m')
        return {
          statement,
          expected,
          actual: sawRuntime ? 'runtime' : 'erased',
          conflicting: sawRuntime && sawErased,
        }
      })
      .filter((row) => row.actual !== row.expected || row.conflicting)
      .map((row) =>
        `${row.statement} => ${row.actual}${row.conflicting ? ' (in BOTH buckets)' : ''}, expected ${row.expected}`,
      )

    expect(mismatches).toEqual([])
  })

  it('has no runtime import cycles', () => {
    expect(runtimeEdgeCount).toBeGreaterThan(200)
    expect(findCycles(runtimeGraph)).toEqual([])
  })

  it('keeps shared independent of features and app, including type references', () => {
    expect(erasedOnlyCount).toBeGreaterThan(0)
    expect(crossLayerEdges).toEqual([])
  })
})
