/**
 * Runtime / knowledge / connection status label maps. Backend enum values
 * stay untouched; only the rendered text is mapped here, so every surface
 * renders the same word for the same state.
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

/** Connection card status line: the enabled flag dominates the enum. */
export function connectionStatusText(enabled: boolean, status: string): string {
  if (enabled) return '已启用'
  if (status === 'FAILED') return '连接失败'
  if (status === 'CREATED') return '未测试'
  return '已禁用'
}
