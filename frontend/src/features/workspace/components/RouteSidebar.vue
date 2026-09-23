<script setup lang="ts">
import { computed } from 'vue'
import { ROUTE_LIFECYCLE_META, routeDisplayName, routeLifecycleLabel } from '@/features/workspace/presentation/routePresentation'
import type { GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/shared/contracts/types'
import { useGraphUiStore } from '@/features/workspace/state/graphUiStore'
import type { GraphRouteDisplayState } from '@/features/workspace/graph/graphTypes'

/**
 * Left route navigator for the graph workspace.
 *
 * Two concepts only — 定位 (viewport) and 只看这条路线 (single-route lens) — plus
 * the Runtime lifecycle commands (设为运行路线 / 恢复 / 归档并隐藏). Reading Focus
 * is set by clicking a route card (`openRoute`), so no separate "浏览此路线"
 * toggle is needed. The separate 弱化 / 隐藏 / 删除 actions were removed: 归档 now
 * defaults to hidden, and per-route dimming duplicated the lifecycle filters.
 *
 * 只看这条路线 is a toggle: the same menu item reads 退出只看 while the lens is
 * active, and clicking another route card moves the lens (never a silent no-op).
 * The lens is the one view state that also hides the running route, and it
 * always carries Focus with it.
 *
 * View-only state (定位/只看/筛选) lives in graphUiStore and never touches
 * Runtime state; Runtime route actions are emitted to the workspace shell. The
 * Active route can never be hidden manually — only the isolate lens may hide it.
 */
const props = defineProps<{
  routes: GraphWorkspaceRouteView[]
  activeRouteId: string | null
  commandPending: boolean
  pendingRouteCommand: string | null
  /** tip 为"未回答问题"的路线：起草下一个问题注定被不变式拒绝，入口置灰。 */
  draftBlockedRouteIds?: string[]
}>()

const emit = defineEmits<{
  'locate-route': [routeId: string]
  activate: [routeId: string]
  restore: [routeId: string]
  archive: [routeId: string]
  /** 在这条 OPEN 路线上起草下一个问题（显式路线 run，不改 Active 指针）。 */
  'draft-next': [routeId: string]
}>()

const graphUi = useGraphUiStore()

function routeBadgeClass(status: RouteLifecycleStatus): string {
  return ROUTE_LIFECYCLE_META.find((meta) => meta.status === status)?.badgeClass ?? 'badge-neutral'
}

const filterOptions = ROUTE_LIFECYCLE_META.map(({ status, label }) => ({ status, label }))

const sortedRoutes = computed(() => props.routes)

function displayState(routeId: string): GraphRouteDisplayState {
  return graphUi.routeDisplayStates[routeId] ?? 'normal'
}

function isFocused(routeId: string): boolean {
  return graphUi.focusRouteId === routeId
}

function isIsolated(routeId: string): boolean {
  return graphUi.isolatedRouteId === routeId
}

/** Focus must never point at a route that a lifecycle filter has hidden. */
function setFilter(status: RouteLifecycleStatus, visible: boolean): void {
  if (!visible) {
    const focused = props.routes.find((route) => route.id === graphUi.focusRouteId)
    if (focused?.lifecycleStatus === status) {
      graphUi.clearFocusRoute()
    }
  }
  graphUi.setLifecycleFilter(status, visible)
}

function isolateRoute(route: GraphWorkspaceRouteView): void {
  if (graphUi.isolatedRouteId === route.id) {
    graphUi.clearIsolation()
    return
  }
  graphUi.isolateRoute(route.id)
}

/**
 * The card is secondary navigation: reading Focus plus viewport location.
 *
 * While the isolate lens is on, every other route is off-canvas, so a plain
 * "focus + locate" would target an invisible route. Clicking a different card
 * therefore moves the lens instead of silently doing nothing; clicking the
 * isolated card itself keeps the lens.
 */
function openRoute(route: GraphWorkspaceRouteView): void {
  if (graphUi.isRouteHidden(route.id) || !graphUi.lifecycleFilters[route.lifecycleStatus]) {
    return
  }
  if (graphUi.isolatedRouteId && graphUi.isolatedRouteId !== route.id) {
    graphUi.isolateRoute(route.id)
  } else {
    graphUi.setFocusRoute(route.id)
  }
  emit('locate-route', route.id)
}
</script>

<template>
  <div class="route-sidebar" data-test="route-sidebar">
    <details class="route-sidebar__section route-sidebar__filters" data-test="route-filters">
      <summary class="route-sidebar__section-summary">筛选<span class="chevron" aria-hidden="true">▾</span></summary>
      <div class="route-sidebar__filter-list">
        <label v-for="filter in filterOptions" :key="filter.status" class="route-sidebar__filter">
          <input
            type="checkbox"
            :checked="graphUi.lifecycleFilters[filter.status]"
            :data-test="`filter-${filter.status}`"
            @change="setFilter(filter.status, ($event.target as HTMLInputElement).checked)"
          />
          {{ filter.label }}
        </label>
      </div>
    </details>

    <section class="route-sidebar__section" data-test="route-list">
      <h3 class="route-sidebar__heading">路线</h3>
      <article
        v-for="route in sortedRoutes"
        :key="route.id"
        class="route-card"
        :class="[
          `route-card--${displayState(route.id)}`,
          { 'route-card--focused': isFocused(route.id), 'route-card--isolated': isIsolated(route.id) },
        ]"
         :data-route-id="route.id"
         tabindex="0"
         :aria-current="isFocused(route.id) ? 'location' : undefined"
         :aria-label="`${routeDisplayName(route)}${isFocused(route.id) ? '（正在浏览）' : ''}${route.id === activeRouteId ? '（运行路线）' : ''}`"
         @click="openRoute(route)"
         @keydown.enter.self.prevent="openRoute(route)"
         @keydown.space.self.prevent="openRoute(route)"
      >
        <div class="route-card__primary" data-test="route-primary">
          <div class="route-card__identity">
            <strong class="route-card__label" :title="routeDisplayName(route)">{{ routeDisplayName(route) }}</strong>
            <span class="meta-text">{{ route.lineageNodeIds.length }} 个节点</span>
          </div>
          <div class="route-card__state">
            <span v-if="isIsolated(route.id)" class="route-isolate-indicator" data-test="isolate-route-label">
              <span class="route-isolate-indicator__dot" aria-hidden="true" />只看中
            </span>
            <span v-if="isFocused(route.id)" class="route-focus-indicator" data-test="focus-route-label">
              <span class="route-focus-indicator__dot" aria-hidden="true" />正在浏览
            </span>
            <span v-if="route.id === activeRouteId" class="route-active-indicator" data-test="active-route">
              <span class="route-active-indicator__dot" aria-hidden="true" />运行路线
            </span>
            <span
              v-else-if="route.lifecycleStatus !== 'open'"
              class="badge"
              :class="routeBadgeClass(route.lifecycleStatus)"
            >
              {{ routeLifecycleLabel(route.lifecycleStatus) }}
            </span>
          </div>
        </div>

        <details class="route-card__more" data-test="route-more" @click.stop>
          <summary class="route-card__more-trigger" aria-label="路线更多操作">···</summary>

          <div class="route-card__menu">
            <div class="route-card__group" data-test="view-actions-group">
              <button class="route-card__menu-item" data-test="locate-route" @click="emit('locate-route', route.id)">定位路线</button>
              <button class="route-card__menu-item" data-test="isolate-route" @click="isolateRoute(route)">{{ isIsolated(route.id) ? '退出只看' : '只看这条路线' }}</button>
            </div>

            <div class="route-card__group" data-test="runtime-actions-group">
              <button v-if="route.lifecycleStatus === 'open'" class="route-card__menu-item" data-test="draft-next-route" :disabled="commandPending || (props.draftBlockedRouteIds ?? []).includes(route.id)" :title="(props.draftBlockedRouteIds ?? []).includes(route.id) ? '当前问题还没有回答，请先回答后再起草下一个问题' : '让 AI 在这条路线起草下一个问题'" @click="emit('draft-next', route.id)">起草下一个问题</button>
              <button v-if="route.lifecycleStatus === 'open' && !route.isActive" class="route-card__menu-item" data-test="activate-route" :disabled="commandPending" @click="emit('activate', route.id)">设为运行路线</button>
              <button v-if="route.lifecycleStatus !== 'open'" class="route-card__menu-item" data-test="restore-route" :disabled="commandPending" @click="emit('restore', route.id)">恢复路线</button>
              <button v-if="route.lifecycleStatus !== 'archived'" class="route-card__menu-item" data-test="archive-route" title="归档后会从默认视图中隐藏，可在「已归档」筛选中找回" :disabled="commandPending" @click="emit('archive', route.id)">归档并隐藏</button>
            </div>
          </div>
        </details>
      </article>
      <p v-if="routes.length === 0" class="muted">暂无路线</p>
    </section>
  </div>
</template>
