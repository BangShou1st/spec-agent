/**
 * Presentation-layer copy and formatting for Skills / Connections management.
 * Backend enums stay untouched; only the rendered text is mapped here.
 */

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
  if (!value) return '—'
  const t = value.replace('T', ' ').slice(0, 16)
  return t || value
}

