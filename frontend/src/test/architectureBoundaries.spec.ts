/**
 * Frontend structure boundaries, asserted against the real source tree.
 *
 * The backend has an ArchUnit gate for this; the frontend had none, which is
 * how scattered directories appeared in the first place. Two rules are
 * enforced here:
 *
 *  1. No runtime import cycles between src modules. `import type` (and fully
 *     type-marked named imports) is erased, so those edges are excluded: a
 *     type-only cycle is not a runtime cycle.
 *  2. `shared/` never depends on `features/` or `app/`. Shared capabilities are
 *     the bottom layer — features depend on them, never the other way round.
 *
 * Test files are excluded from the graph: they import production modules by
 * design and are never part of a shipped bundle.
 */
import { describe, expect, it } from 'vitest'

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

const VALUE_IMPORT = /(?:import|export)\s+(?!type\s)[^;]*?from\s+['"]([^'"]+)['"]/gs
const BARE_IMPORT = /(?:^|[\s;])import\s+['"]([^'"]+)['"]/gm
const DYNAMIC_IMPORT = /import\s*\(\s*['"]([^'"]+)['"]\s*\)/g
const NAMED_IMPORT = /import\s*\{\s*([^}]*)\}\s*from\s+['"]([^'"]+)['"]/gs
const TYPE_IMPORT = /import\s+type\s+[^;]*?from\s+['"]([^'"]+)['"]/gs

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

/** Specifiers erased at compile time, never producing a runtime edge. */
function typeOnlySpecifiers(text: string): Set<string> {
  const erased = new Set<string>()
  for (const match of text.matchAll(TYPE_IMPORT)) erased.add(match[1])
  for (const match of text.matchAll(NAMED_IMPORT)) {
    const names = match[1].split(',').map((name) => name.trim()).filter(Boolean)
    if (names.length > 0 && names.every((name) => name.startsWith('type '))) erased.add(match[2])
  }
  return erased
}

function buildGraph(): Graph {
  const graph: Graph = new Map()

  for (const importer of shipped) {
    const text = modules.get(importer) ?? ''
    const erased = typeOnlySpecifiers(text)
    const outgoing = new Set<string>()

    for (const pattern of [VALUE_IMPORT, BARE_IMPORT, DYNAMIC_IMPORT]) {
      for (const match of text.matchAll(pattern)) {
        if (erased.has(match[1])) continue
        const target = resolve(importer, match[1])
        if (target !== null && target !== importer) outgoing.add(target)
      }
    }

    graph.set(importer, outgoing)
  }

  return graph
}

function findCycles(graph: Graph): string[] {
  const state = new Map<string, 'visiting' | 'done'>()
  const stack: string[] = []
  const cycles: string[][] = []

  const visit = (node: string): void => {
    state.set(node, 'visiting')
    stack.push(node)
    for (const next of graph.get(node) ?? []) {
      if (state.get(next) === 'visiting') {
        cycles.push([...stack.slice(stack.indexOf(next)), next])
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
  return cycles.map((cycle) => cycle.join(' -> '))
}

const graph = buildGraph()

const crossLayerEdges = [...graph.entries()]
  .filter(([from]) => from.startsWith('src/shared/'))
  .flatMap(([from, targets]) =>
    [...targets]
      .filter((to) => to.startsWith('src/features/') || to.startsWith('src/app/'))
      .map((to) => `${from} -> ${to}`),
  )

describe('frontend structure boundaries', () => {
  it('scans the real source tree, not an empty glob', () => {
    expect(shipped.length).toBeGreaterThan(120)
    expect(shipped.some((path) => path.startsWith('src/features/'))).toBe(true)
    expect(shipped.some((path) => path.startsWith('src/shared/'))).toBe(true)
    expect(shipped.some((path) => path.startsWith('src/app/'))).toBe(true)
    expect(graph.size).toBe(shipped.length)
  })

  it('has no runtime import cycles', () => {
    expect(findCycles(graph)).toEqual([])
  })

  it('keeps shared independent of features and app', () => {
    expect(crossLayerEdges).toEqual([])
  })
})
