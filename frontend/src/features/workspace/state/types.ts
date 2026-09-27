// 文件名:types.ts
// 用途:工作区 store 级的领域类型:在途路线命令、手动模型重试意图、以及一次回答提交尝试(AnswerRunSession)的完整生命周期状态,供 store 与各领域模块共享。
/*
 * `workspaceStore.ts` 与 `state/` 下各领域模块共享的 store 级领域类型。
 *
 * 它们过去声明在 `workspaceStore.ts` 内部。它们是不访问 store 的纯类型
 * 声明,所以领域模块从这里导入——仅类型导入让 store 与领域模块的关系
 * 不含任何运行时循环依赖。`workspaceStore.ts` 重新导出它们,store 的公开
 * 类型表面保持不变。
 */
import type { RegenerateNodeRequest, SubmitAnswerRequest } from '@/shared/contracts/types'

/** 精确的在途路线命令,用于 pending 文案与按钮锁定。 */
export type PendingRouteCommand =
  | 'activate'
  | 'restore'
  | 'archive'
  | 'delete'
  | 'fork'
  | 'reanswer'
  | 'regenerate'
  | null

export type RetryState = 'ready' | 'needs_reconcile' | 'ambiguous'

export type ManualModelRetryIntent =
  | {
      kind: 'draft'
      beforeRouteId: string | null
      beforeTipNodeId: string | null
      state: RetryState
    }
  | {
      kind: 'spec'
      routeId: string
      beforeSpecIds: string[]
      state: RetryState
    }
  | {
      kind: 'regenerate'
      nodeId: string
      payload: RegenerateNodeRequest
      beforeRouteIds: string[]
      beforeActiveRouteId: string | null
      state: RetryState
    }

export type MutationFocusTarget = {
  routeId: string
  nodeId: string | null
}

/*
 * 一次回答提交尝试的完整生命周期(一次用户动作、一个客户端请求 id、一条
 * 后端 run 链)。
 *
 * 这次尝试的每个生命周期事实都放在会话上——绝不放在共享的单值 store
 * 字段上——因此不同路线上的两个并发回答绝不可能清除、覆盖或恢复对方的
 * 状态:
 * - 成功清理只移除它自己的会话,只清除它自己的草稿(提交时捕获的
 *   projectId + routeId + nodeId),
 * - 失败 / 结果未知的恢复只读写它自己的会话,
 * - 重提交只复用它自己的载荷。
 */
export type AnswerRunSessionStatus =
  /** 创建请求已发送 / run 轮询中;该路线被锁定,禁止第二个周期。 */
  | 'RUNNING'
  /** 回答已持久化但后续生成未完成 → 需要修复。 */
  | 'REPAIRABLE'
  /** canonical 读取证明什么都没落地 → 可证明安全的一次性重提交。 */
  | 'RESUBMITTABLE'
  /** 终态无法观测 → 任何重试之前先对账。 */
  | 'UNKNOWN'

/** 一次被观察的回答 run 尝试。完全处理完后从 store 移除。 */
export interface AnswerRunSessionState {
  /** 提交时生成的客户端请求身份(幂等键)。 */
  clientRequestId: string
  /** 提交动作开始时所在的项目——整个生命周期固定。 */
  projectId: string
  /** 被回答节点在提交时所属的路线(清理身份)。 */
  routeId: string | null
  nodeId: string
  /** 原始载荷;这次尝试唯一可证明安全的重提交体。 */
  payload: SubmitAnswerRequest
  /** create 调用返回后的后端 run id;此前为 null。 */
  runId: string | null
  /** 最近观察到的 run 阶段(进度展示)。 */
  phase: string | null
  /** 最近观察到的运行时状态(进度展示)。 */
  runStatus: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | null
  status: AnswerRunSessionStatus
  /** 回答已持久化但后续生成未完成时设置。 */
  repairableAnswerId: string | null
  /** 此会话修复的是历史检查点而非实时末端时为 true。 */
  historicalRecovery?: boolean
}
