// 文件名:managementCopy.ts
// 用途:Skills / 连接管理页的展示层文案与格式化:来源/连接类型中文标签、字节与日期格式化;后端枚举原样保留,只映射渲染文本。
/*
 * Skills / Connections 管理的展示层文案与格式化。
 * 后端枚举值原样保留;只在这里映射渲染文本。
 */

import { formatShanghaiDateTime } from './formatTime'

const SKILL_SOURCE_LABELS: Record<string, string> = {
  BUILTIN: '内置',
  GIT: 'Git',
  GIT_HTTPS: 'Git',
  UPLOAD_ZIP: 'ZIP',
}

const CONNECTION_KIND_LABELS: Record<string, string> = {
  CUSTOM_MCP: '自定义连接',
  SYSTEM_SUPPORTED: '系统连接',
}

export function skillSourceLabel(sourceKind: string): string {
  return SKILL_SOURCE_LABELS[sourceKind] ?? sourceKind
}

export function connectionKindLabel(kind: string): string {
  return CONNECTION_KIND_LABELS[kind] ?? kind
}

export function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function formatDateTime(value: string): string {
  return formatShanghaiDateTime(value)
}

