// 文件名:statusCopy.ts
// 用途:运行时/知识/连接状态的中文标签映射:后端枚举值原样保留,只映射展示文本,保证每个界面同一状态显示同一个词。
/*
 * 运行时 / 知识 / 连接状态标签映射。后端枚举值原样保留;只在这里映射
 * 渲染文本,因此每个表面为同一状态渲染同一个词。
 */

const KNOWLEDGE_STATUS_LABELS: Record<string, string> = {
  PROPOSED: '待确认',
  CONFIRMED: '已确认',
  CHALLENGED: '有质疑',
  SUPERSEDED: '已替代',
}

export function knowledgeStatusLabel(status: string | null | undefined): string | null {
  if (!status) return null
  return KNOWLEDGE_STATUS_LABELS[status] ?? null
}

const RUNTIME_STATUS_LABELS: Record<string, string> = {
  FAILED: '需处理',
  RUNNING: '生成中',
  PENDING: '待处理',
}

export function runtimeStatusLabel(status: string): string | null {
  return RUNTIME_STATUS_LABELS[status] ?? null
}

/** 连接卡片的状态行:enabled 标志优先于枚举。 */
export function connectionStatusText(enabled: boolean, status: string): string {
  if (enabled) return '已启用'
  if (status === 'FAILED') return '连接失败'
  if (status === 'CREATED') return '未测试'
  return '已禁用'
}
