// 文件名:shared.ts
// 用途:工作区领域模块共享的、与 store 无关的辅助函数与常量:项目会话捕获、提案列表的 fail-soft 读取、冲突类错误的可回答节点提示。
/*
 * 工作区领域模块共享的、与 store 无关的辅助函数。
 *
 * 这些函数过去位于 `workspaceStore.ts` 顶部。它们是不访问 store 的纯函数
 * /常量,所以应该放在领域模块旁边,而不是 store 定义内部。
 */
import type { DisplayError } from '@/shared/http/displayError'
import { listProposals } from '@/features/workspace/api/graphCommands'
import type { ProjectProposalSummary } from '@/features/workspace/api/graphCommands'
import type { ProjectSessionSlice } from './slices'

/*
 * 在异步 action 开始的瞬间捕获 store 的项目会话身份。`isCurrent()` 必须在
 * 每个 `await` 之后、任何 store 写入之前调用(包括 catch/finally 路径):
 * 它同时校验项目会话计数器与项目 id,因此针对项目 A 的慢响应绝不能把
 * 状态、反馈或错误写进项目 B——包括 A→B→A 切换与同项目刷新,只有计数器
 * 才能区分它们。
 */
export function captureProjectSession(store: ProjectSessionSlice): {
  projectId: string
  isCurrent: () => boolean
} {
  const projectId = store.projectId ?? ''
  const session = store.projectSessionId
  return {
    projectId,
    isCurrent: () =>
      store.projectSessionId === session
      && store.projectId === projectId
      && projectId !== '',
  }
}

/*
 * 把提案标记为 NodeInspector 上下文"问 AI"产出的触发类型。提案列表 API
 * 被所有 run 类型共用,恢复流程绝不能从 inputNodeId 推断查询来源(每种
 * run 类型都带一个),因此这个显式的 AgentRunTriggerType 代码是唯一安全
 * 的判别符。
 */
export const NODE_QUERY_TRIGGER = 'node_query'

/*
 * fail-soft 地读取一个项目的持久 PROPOSED NodeQuery 提案:提案列表读取
 * 失败绝不能让工作区加载或刷新失败(待确认提案在下次成功加载时仍可发现)。
 * 纯函数形态让 loadWorkspace/refreshWorkspace 能把请求与 canonical 读取
 * 放进同一个 Promise.all,工作区关键路径及其加载/布局时序保持不变。
 *
 * 收窄由后端完成(见 ProposalTriggerFilter),而不是在这里对全量列表做
 * 后过滤:客户端不再下载会丢弃的提案,"哪类 run 属于哪个表面"的决策也
 * 留在数据旁边。
 */
export function loadNodeQueryProposalsSafely(projectId: string): Promise<ProjectProposalSummary[]> {
  return listProposals(projectId, 'PROPOSED', { triggerTypes: [NODE_QUERY_TRIGGER] })
    .catch(() => [])
}

/**
 * 回答/决策周期产生的"待确认提案"（CREATE_NODE / CONNECT_NODE 等意图变更，
 * 策略层要求用户显式确认后才执行）。与 node_query 提案互斥：后者由
 * Inspector 的问 AI 流程消费，这里的进入全局确认区。互补关系由后端的
 * excludeTriggerType 表达，而不是本地过滤。
 */
export function loadConfirmableProposalsSafely(projectId: string): Promise<ProjectProposalSummary[]> {
  return listProposals(projectId, 'PROPOSED', {
    excludeTriggerTypes: [NODE_QUERY_TRIGGER],
  }).catch(() => [])
}

/**
 * 冲突类错误码：请求与运行时状态相撞（路线被接替/指针悬空/目标过期）。
 * 这类错误的共同解法是"去操作当前最新的可回答问题节点"，因此展示层统一
 * 追加该节点的可读指引，而不是让用户对着一句抽象的冲突描述猜。
 */
const CONFLICT_HINT_CODES = new Set([
  'RUNTIME_CONFLICT',
  'ROUTE_NOT_OPEN',
  'ROUTE_NOT_ACTIVATABLE',
  'AGENT_RUN_TARGET_STALE',
])

/*
 * 把冲突类错误的提示补上当前可回答问题的标题,让横幅直接点名用户应当
 * 操作的节点。尽力而为:提示来自最后一次成功的工作区读取,该读取没有
 * 可回答节点时完全跳过——过期的提示不如没有提示。
 */
export function withAnswerableNodeHint(
  error: DisplayError,
  answerableQuestion: string | null,
): DisplayError {
  if (!CONFLICT_HINT_CODES.has(error.code) || !answerableQuestion) return error
  const trimmed = answerableQuestion.trim()
  if (!trimmed) return error
  return {
    ...error,
    message: `${error.message}。当前等待回答的问题是「${trimmed.slice(0, 40)}」，可直接在该节点上作答`,
  }
}
