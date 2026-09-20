import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

/**
 * 节点操作配置表 —— 画布节点卡与 NodeInspector 共用的"这个节点现在能做什么"。
 *
 * 类型能力差异（不要抹平的领域规则）在这里集中表达：INTERACTION 的历史卡才有
 * fork/重答/换题；作答永远在卡片内，不进操作表；未回答的 tip 不提供"起草下一
 * 个问题"；浮动节点不提供"接入"按钮——接入只允许用户手动拖连线，按钮只提供
 * "继续生成问题"（浮动=开新路线，已接入=开新分支）。新增节点类型只需
 * 在 {@link actionsFor} 里注册一组动作，宿主组件按 id 分发即可。
 */

export type NodeActionId =
  | 'edit-draft'
  | 'confirm-knowledge'
  | 'draft-from-node'
  | 'continue-node'
  | 'draft-next-question'
  | 'fork-node'
  | 'reanswer-node'
  | 'regenerate-node'
  | 'disconnect-node'
  | 'contextual-ai'

export interface NodeAction {
  id: NodeActionId
  label: string
  title: string
  disabled?: boolean
}

export interface NodeActionContext {
  graphCommandPending?: boolean
  drafting?: boolean
  /** 该节点的 run 在途（锁定问题卡按钮）。 */
  runPending?: boolean
}

function contentTextOf(data: SpecAgentGraphNodeData): string {
  const text = data.node.content?.text
  return typeof text === 'string' ? text : ''
}

export function actionsFor(
  data: SpecAgentGraphNodeData,
  ctx: NodeActionContext = {},
): NodeAction[] {
  const node = data.node
  const commandPending = ctx.graphCommandPending ?? false
  const runPending = ctx.runPending ?? false

  if (node.kind === 'INTERACTION') {
    // 当前待回答节点直接在卡片内作答，不提供操作轨道。
    if (data.canAnswer) return []
    const actions: NodeAction[] = []
    const isAnsweredTip = data.isTipOfReadingRoute === true
      && data.readingRouteId !== null
      && data.primaryAnswer !== null
    if (isAnsweredTip) {
      actions.push({
        id: 'draft-next-question',
        label: '起草下一个问题',
        title: '回答已完成：让 AI 在这条路线起草下一个问题',
        disabled: runPending,
      })
    }
    const isAnsweredHistorical = !isAnsweredTip && data.primaryAnswer !== null
    actions.push(
      isAnsweredHistorical
        ? {
            id: 'fork-node',
            label: '继续生成问题',
            title: '从该节点开一条新分支，让 AI 据此起草下一个问题',
          }
        : { id: 'fork-node', label: '从这里开新路线', title: '我接受现在，换未来' },
      { id: 'reanswer-node', label: '重新选择答案', title: '问题没错，答案换一个' },
      {
        id: 'regenerate-node',
        label: '换一个问题',
        title: '问题本身换掉',
        disabled: node.parentNodeId === null || runPending,
      },
      { id: 'contextual-ai', label: '问 AI', title: '在检查器中询问 AI' },
    )
    if (data.isTipOfReadingRoute === true && data.readingRouteId !== null) {
      actions.push({
        id: 'disconnect-node',
        label: '断开接入',
        title: '从当前路线断开，回到独立节点（内容保留）',
        disabled: commandPending,
      })
    }
    return actions
  }

  // 非交互节点（知识草稿 / 资源 / 工件）。
  const isDraft = node.userEditableDraft
  const contentText = contentTextOf(data)
  /** 空 idea 谈不上"据此提问"；资源卡（文件/链接等）内容天然非文本，直接放行。 */
  const hasContent = node.kind === 'RESOURCE' || contentText.trim().length > 0
  const isFloating = (data.routeIds?.length ?? 0) === 0
  const actions: NodeAction[] = []

  if (isDraft) {
    actions.push({
      id: 'edit-draft',
      label: '编辑',
      title: '编辑草稿内容',
      disabled: commandPending,
    })
  }
  if (isDraft && node.knowledgeStatus === 'PROPOSED' && contentText) {
    actions.push({
      id: 'confirm-knowledge',
      label: '确认内容',
      title: '确认这条内容为可信知识',
      disabled: commandPending,
    })
  }

  // "继续生成问题"：所有想法都可以据此让 AI 起草下一个问题。
  // 浮动节点 = 以它为起点创建一条新路线；已接入路线 = 在该节点开新分支。
  // 接入本身不再提供按钮——把卡片侧面的连线拖到路线末端即可。
  if (hasContent) {
    actions.push({
      id: 'draft-from-node',
      label: '继续生成问题',
      title: isFloating
        ? '以这个想法为起点创建一条新路线，并让 AI 起草下一个问题'
        : data.readingRouteId
          ? '从该节点开一条新分支，让 AI 起草下一个问题'
          : '共享节点请先在上方选择查看路线',
      disabled: commandPending || (!isFloating && !data.readingRouteId),
    })
  }

  if (!isFloating) {
    actions.push({
      id: 'continue-node',
      label: '从这里继续',
      title: data.readingRouteId
        ? '从该节点继续探索（历史节点将创建探索分支）'
        : '共享节点请先选择查看路线',
      disabled: !data.readingRouteId || commandPending,
    })
    if (data.isTipOfReadingRoute === true && data.readingRouteId !== null && hasContent) {
      actions.push({
        id: 'draft-next-question',
        label: '起草下一个问题',
        title: '这是当前路线末端：让 AI 据此起草下一个问题',
        disabled: (ctx.drafting ?? false) || commandPending,
      })
    }
    // 已接入的知识/资源（末端或挂在谱系下的出处节点）都可断开回独立节点；
    // 链中间的历史节点由后端拒绝并给出明确错误。
    if (!isFloating && data.readingRouteId !== null) {
      actions.push({
        id: 'disconnect-node',
        label: '断开接入',
        title: '从当前路线断开，回到独立节点（内容保留）',
        disabled: commandPending,
      })
    }
  }

  actions.push({ id: 'contextual-ai', label: '问 AI', title: '在检查器中询问 AI' })
  return actions
}
