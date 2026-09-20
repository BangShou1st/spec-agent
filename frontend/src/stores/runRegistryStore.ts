/**
 * Registry of AgentRuns currently observed by the workspace.
 *
 * Single responsibility: hold one entry per known run (operation, route,
 * source node, phase, composed progress) and keep it up to date from run
 * read views. It knows nothing about answer semantics, pending cards or
 * repair flows — workspaceStore feeds it and the presentation layer reads it.
 *
 * 并发模型：每条路线的 run 独立成条目，多个并发 run 互不覆盖。
 */
import { defineStore } from 'pinia'
import type { AgentRunView, RunProgressStep } from '@/api/agentRuns'
import type { GraphRuntimeStatus } from '@/graph/graphProjection'

/** One observed run. `sourceNodeId` is the node the run was started from
 * (known at submit time; absent for runs rebuilt after a page reload). */
export interface RunRegistryEntry {
  runId: string
  operation: string
  routeId: string | null
  sourceNodeId: string | null
  status: GraphRuntimeStatus
  phase: string | null
  summary: string | null
  steps: RunProgressStep[]
}

function runtimeStatusOf(status: AgentRunView['status']): GraphRuntimeStatus {
  return status === 'failed'
    ? 'FAILED'
    : status === 'completed'
      ? 'SUCCEEDED'
      : status === 'created'
        ? 'PENDING'
        : 'RUNNING'
}

export const useRunRegistryStore = defineStore('runRegistry', {
  state: () => ({
    runs: {} as Record<string, RunRegistryEntry>,
  }),
  getters: {
    /** Non-terminal runs first, then failures — a stable display order. */
    list(state): RunRegistryEntry[] {
      return Object.values(state.runs)
    },
    inFlight(): RunRegistryEntry[] {
      return this.list.filter((entry) => entry.status === 'PENDING' || entry.status === 'RUNNING')
    },
  },
  actions: {
    /** Registers a run whose identity is known before the first poll
     * (submit-time facts: route + source node). */
    register(entry: {
      runId: string
      operation: string
      routeId: string | null
      sourceNodeId: string | null
    }): void {
      const existing = this.runs[entry.runId]
      this.runs[entry.runId] = {
        ...existing,
        ...entry,
        status: existing?.status ?? 'PENDING',
        phase: existing?.phase ?? null,
        summary: existing?.summary ?? null,
        steps: existing?.steps ?? [],
      }
    },

    /** Upserts one run read view (poll callback). Never overwrites a known
     * sourceNodeId — rebuilt entries may learn it later, never lose it. */
    feed(view: AgentRunView): void {
      const existing = this.runs[view.runId]
      this.runs[view.runId] = {
        runId: view.runId,
        operation: view.operation || existing?.operation || '',
        routeId: view.routeId ?? existing?.routeId ?? null,
        sourceNodeId: existing?.sourceNodeId ?? null,
        status: runtimeStatusOf(view.status),
        phase: view.phase || existing?.phase || null,
        summary: view.progress?.summary ?? existing?.summary ?? null,
        steps: view.progress?.steps ?? existing?.steps ?? [],
      }
    },

    /**
     * Reconciles the registry with the backend active-runs listing (page
     * reload / workspace refresh). Backend truth is upserted; submit-time
     * facts (sourceNodeId) survive; runs still absent from the listing stay
     * tracked while a poll observes them, except SUCCEEDED ones — success is
     * fully reflected in the canonical graph after the refresh, so the entry
     * is dropped.
     */
    rebuild(views: AgentRunView[]): void {
      const next: Record<string, RunRegistryEntry> = {}
      const listed = new Set<string>()
      for (const view of views) {
        listed.add(view.runId)
        const existing = this.runs[view.runId]
        next[view.runId] = {
          runId: view.runId,
          operation: view.operation || existing?.operation || '',
          routeId: view.routeId ?? existing?.routeId ?? null,
          sourceNodeId: existing?.sourceNodeId ?? null,
          status: runtimeStatusOf(view.status),
          phase: view.phase || null,
          summary: view.progress?.summary ?? null,
          steps: view.progress?.steps ?? [],
        }
      }
      for (const entry of Object.values(this.runs)) {
        if (listed.has(entry.runId)) continue
        if (entry.status === 'SUCCEEDED') continue
        next[entry.runId] = entry
      }
      this.runs = next
    },

    remove(runId: string): void {
      delete this.runs[runId]
    },

    clear(): void {
      this.runs = {}
    },
  },
})
