/**
 * Frontend structure boundaries, asserted against the real source tree.
 *
 * The backend has an ArchUnit gate for this; the frontend had none, which is
 * how scattered directories appeared in the first place. Two rules are
 * enforced here:
 *
 *  1. No runtime import cycles between src modules. An edge is a *runtime* edge
 *     only when the statement survives compilation — `import type`, a fully
 *     type-marked named import, and type-position `import('x')` are all erased,
 *     so they cannot close a runtime cycle.
 *  2. `shared/` never reaches into `features/` or `app/`. This rule counts
 *     type-only references too: letting `shared` depend on a business type
 *     drags the bottom layer back up the graph and is the same coupling in a
 *     cheaper disguise.
 *
 * Classification is done per statement with the TypeScript parser rather than
 * per specifier string: `import type { A } from './m'` followed by
 * `import { b } from './m'` is one erased import and one real runtime import,
 * and a string-keyed "type-only" set would wrongly drop the latter.
 *
 * Test files are excluded from the graph: they import production modules by
 * design and are never part of a shipped bundle.
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

/** `../features/workspace/WorkspaceView.vue` -> `src/features/workspace/WorkspaceView.vue` */
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

/** The script section of a single-file component, or the file itself for `.ts`. */
function scriptOf(path: string, text: string): string {
  if (!path.endsWith('.vue')) return text
  const blocks = [...text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1])
  return blocks.join('\n')
}

/** Resolve a specifier to a tracked module, or null when it is external/CSS. */
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
  /** Statements that still exist after compilation. */
  runtime: string[]
  /** Statements the compiler removes. */
  erased: string[]
}

/** An import declaration whose bindings are all type-only is erased entirely. */
function importIsErased(node: ts.ImportDeclaration): boolean {
  const clause = node.importClause
  if (clause === undefined) return false // bare `import './x'` is a real side effect
  if (clause.isTypeOnly) return true
  const bindings = clause.namedBindings
  if (bindings === undefined || !ts.isNamedImports(bindings)) return false
  return bindings.elements.length > 0 && bindings.elements.every((element) => element.isTypeOnly)
}

/** Same rule for `export { ... } from` / `export type { ... } from`. */
function exportIsErased(node: ts.ExportDeclaration): boolean {
  if (node.isTypeOnly) return true
  const clause = node.exportClause
  if (clause === undefined || !ts.isNamedExports(clause)) return false
  return clause.elements.length > 0 && clause.elements.every((element) => element.isTypeOnly)
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

  // `import('x')` can appear anywhere: a runtime call, or a type-position query.
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
/** Runtime plus erased references: the layering rule cares about both. */
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
    // Regression guard for the bug this gate used to have: an erased import and a
    // real import of the SAME path must not collapse into "erased".
    const probe = [
      "import type { OnlyType } from './probe-target'",
      "import { realValue } from './probe-target'",
    ].join('\n')

    const { runtime, erased } = classify('src/__classify_probe__.ts', probe)
    expect(erased).toEqual(['./probe-target'])
    expect(runtime).toEqual(['./probe-target'])
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
