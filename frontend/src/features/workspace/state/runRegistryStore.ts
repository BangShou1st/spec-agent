// 文件名:runRegistryStore.ts
// 用途:工作台正在观察的 AgentRun 注册表 store:每条已知 run 一条目(操作/路线/来源节点/阶段/汇总进度)并随 run 读取视图更新;不关心回答语义,presentation 层从它读取。
/*
 * 工作台当前观察的 AgentRun 注册表。
 *
 * 单一职责:每条已知 run(操作、路线、来源节点、阶段、汇总进度)持有
 * 一个条目,并从 run 读取视图保持其最新。它对回答语义、pending 卡片或
 * 修复流程一无所知——workspaceStore 喂数据,presentation 层来读。
 *
 * 并发模型:每条路线的 run 独立成条目,多个并发 run 互不覆盖。
 */
import { defineStore } from 'pinia'
import type { AgentRunView, RunProgressStep, UnresolvedFailure } from '@/features/workspace/api/agentRuns'
import type { GraphRuntimeStatus } from '@/features/workspace/graph/graphProjection'

/** 一条被观察的 run。`sourceNodeId` 是 run 的发起节点(提交时已知;
 * 页面刷新后重建的条目缺失该字段)。 */
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
    /** 服务端判定的未解决失败,以 failedRunId 为键。任务身份
     * (operation/routeId/sourceNodeId)由后端持久化事实决定,前端绝不猜测。 */
    failures: {} as Record<string, UnresolvedFailure>,
    /** 本会话已发起的重试:failedRunId → 在途重试 runId。乐观标记,
     * 服务端清单刷新后以 retryRunId 为准。 */
    retrying: {} as Record<string, string>,
  }),
  getters: {
    /** 非终态 run 优先,失败其后——稳定的展示顺序。 */
    list(state): RunRegistryEntry[] {
      return Object.values(state.runs)
    },
    inFlight(): RunRegistryEntry[] {
      return this.list.filter((entry) => entry.status === 'PENDING' || entry.status === 'RUNNING')
    },
    failureList(state): UnresolvedFailure[] {
      return Object.values(state.failures)
    },
    /** 节点上的未解决失败:精确绑定 (sourceNodeId, routeId)。共享节点上
     * 不同路线的失败互不覆盖——routeId 为 null 的失败只匹配无路线阅读态。 */
    failuresForNode(): (nodeId: string, routeId: string | null) => UnresolvedFailure[] {
      return (nodeId, routeId) => this.failureList.filter(
        (failure) => failure.sourceNodeId === nodeId
          && (failure.routeId ?? null) === (routeId ?? null),
      )
    },
    /** 失败任务是否有在途重试(乐观标记或服务端 retryRunId)。 */
    isRetrying(): (failedRunId: string) => boolean {
      return (failedRunId) => {
        const failure = this.failures[failedRunId]
        return Boolean(this.retrying[failedRunId] ?? failure?.retryRunId)
      }
    },
  },
  actions: {
    /** 在首次轮询之前注册身份已知的 run(提交时事实:路线 + 来源节点)。 */
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

    /** 插入或更新一条 run 读取视图(轮询回调)。绝不覆盖已知的
     * sourceNodeId——重建的条目可能稍后学到它,但绝不丢失。 */
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

    /*
     * 让注册表与后端的活跃 run 列表对账(页面刷新 / 工作区刷新)。
     * 后端事实以 upsert 写入;提交时事实(sourceNodeId)幸存;列表中暂缺
     * 的 run 在轮询仍观察到它时保持追踪,但 SUCCEEDED 的除外——成功已
     * 完整体现在刷新后的 canonical 图中,条目随之丢弃。
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

    /** 与后端未解决失败清单对账(加载/刷新/重试后)。服务端事实整体
     * 重建;本会话的乐观 retrying 标记在服务端确认 retryRunId 后清除。 */
    rebuildFailures(views: UnresolvedFailure[]): void {
      const next: Record<string, UnresolvedFailure> = {}
      for (const view of views) {
        next[view.runId] = view
      }
      this.failures = next
      for (const [failedRunId, retryRunId] of Object.entries(this.retrying)) {
        const failure = next[failedRunId]
        if (failure?.retryRunId === retryRunId) {
          delete this.retrying[failedRunId]
        } else if (!failure) {
          delete this.retrying[failedRunId]
        }
      }
      // 失败清单是权威:已被成功/更新失败取代的 FAILED run 不再渲染为
      // 失败占位卡(历史仍在服务端保留)。
      for (const [runId, entry] of Object.entries(this.runs)) {
        if (entry.status === 'FAILED' && !next[runId]) {
          delete this.runs[runId]
        }
      }
    },

    /** 乐观标记重试已发起(立即禁用按钮并显示进度)。 */
    markRetryStarted(failedRunId: string, retryRunId: string): void {
      this.retrying[failedRunId] = retryRunId
    },

    /**
     * 请求发出前的即时乐观标记(6-2):在网络请求在途期间就禁用重试按钮
     * 并显示进度,不等响应。服务端确认后以 markRetryStarted 覆盖为真实
     * runId;请求失败时用 clearRetryMark 回滚。幂等标记在服务端清单
     * 对账后以 retryRunId 为准(后端幂等仍保留)。
     */
    markRetryPending(failedRunId: string): void {
      if (!this.retrying[failedRunId]) {
        this.retrying[failedRunId] = 'pending'
      }
    },

    /** 清除即时乐观标记(请求失败/结果未知);已是服务端确认的标记不动。 */
    clearRetryMark(failedRunId: string): void {
      if (this.retrying[failedRunId] === 'pending') {
        delete this.retrying[failedRunId]
      }
    },

    /** 失败已解决(重试成功或失败被取代):从待处理清单移除。 */
    clearFailure(failedRunId: string): void {
      delete this.failures[failedRunId]
      delete this.retrying[failedRunId]
    },

    clear(): void {
      this.runs = {}
      this.failures = {}
      this.retrying = {}
    },
  },
})
