import type { GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/shared/contracts/types'

/**
 * 路线生命周期与节点关系类型的展示文案,全项目唯一定点。
 * label 供徽章/筛选项共用,badgeClass 替代此前的动态类名拼接
 * (`badge-${status}` 的隐式全局类契约)。
 */

export const ROUTE_LIFECYCLE_META: {
  status: RouteLifecycleStatus
  label: string
  badgeClass: string
}[] = [
  { status: 'open', label: '开放', badgeClass: 'badge-open' },
  { status: 'superseded', label: '已替代', badgeClass: 'badge-superseded' },
  { status: 'archived', label: '已归档', badgeClass: 'badge-archived' },
  { status: 'deleted', label: '已删除', badgeClass: 'badge-deleted' },
]

export function routeLifecycleLabel(status: RouteLifecycleStatus): string {
  return ROUTE_LIFECYCLE_META.find((meta) => meta.status === status)?.label ?? status
}

export const RELATION_TYPE_LABELS: Record<string, string> = {
  RELATED_TO: '相关',
  DEPENDS_ON: '依赖',
  DERIVED_FROM: '派生自',
  CONFLICTS_WITH: '冲突',
  SUPPORTS: '支持',
}

export function relationTypeLabel(type: string): string {
  return RELATION_TYPE_LABELS[type] ?? type
}

/** Human-readable route name. Never falls back to a raw id slice: an
 * unlabeled route is described by its branch origin, then by whether it
 * is the Active route. RouteSidebar 与节点卡片（接入路线按钮等）共用。 */
export function routeDisplayName(route: GraphWorkspaceRouteView): string {
  if (route.label?.trim()) return route.label.trim()
  if (route.branchType === 'fork') return '分支路线'
  if (route.branchType === 'reanswer') return '重新回答路线'
  if (route.branchType === 'regenerate') return '换题路线'
  return route.isActive ? '主路线' : '路线'
}
