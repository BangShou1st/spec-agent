<script setup lang="ts">
import { computed } from 'vue'
import type { GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/api/types'
import { useGraphUiStore } from '@/stores/graphUiStore'
import type { GraphRouteDisplayState } from '@/graph/graphTypes'

/**
 * Left route navigator for the graph workspace.
 *
 * View-only controls (定位/聚焦/弱化/隐藏) live in graphUiStore and never
 * touch Runtime state; runtime route actions (设为当前路线/归档/恢复/删除)
 * are emitted to the workspace shell which runs the backend commands. The
 * Active route can never be hidden.
 */
const props = defineProps<{
  routes: GraphWorkspaceRouteView[]
  activeRouteId: string | null
  commandPending: boolean
  pendingRouteCommand: string | null
}>()

const emit = defineEmits<{
  'locate-route': [routeId: string]
  activate: [routeId: string]
  restore: [routeId: string]
  archive: [routeId: string]
  delete: [routeId: string]
}>()

const graphUi = useGraphUiStore()

const lifecycleLabels: Record<RouteLifecycleStatus, string> = {
  open: '开放',
  superseded: '已替代',
  archived: '已归档',
  deleted: '已删除',
}

const filterOptions: { status: RouteLifecycleStatus; label: string }[] = [
  { status: 'open', label: '开放' },
  { status: 'superseded', label: '已替代' },
  { status: 'archived', label: '已归档' },
  { status: 'deleted', label: '已删除' },
]

const sortedRoutes = computed(() => props.routes)

function displayState(routeId: string): GraphRouteDisplayState {
  return graphUi.routeDisplayStates[routeId] ?? 'normal'
}

function isFocused(routeId: string): boolean {
  return graphUi.focusRouteId === routeId
}

/**
 * Focus is only for visible routes: a manually hidden or lifecycle-filtered
 * route can never become the Focus route (graphUiStore enforces the hidden
 * half on its own; the filter half needs the route lifecycle, a Runtime fact
 * that lives here, not in the browser-only UI store).
 */
function toggleFocus(route: GraphWorkspaceRouteView): void {
  if (isFocused(route.id)) {
    graphUi.clearFocusRoute()
    return
  }
  if (graphUi.isRouteHidden(route.id) || !graphUi.lifecycleFilters[route.lifecycleStatus]) {
    return
  }
  graphUi.setFocusRoute(route.id)
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

function setDim(routeId: string, dimmed: boolean): void {
  if (dimmed) graphUi.dimRoute(routeId)
  else graphUi.restoreRouteDisplay(routeId)
}

function setHidden(routeId: string, hidden: boolean): void {
  if (hidden) graphUi.hideRoute(routeId)
  else graphUi.restoreRouteDisplay(routeId)
}

function isolateRoute(route: GraphWorkspaceRouteView): void {
  graphUi.isolateRoute(route.id, props.routes.map((candidate) => candidate.id))
}

function isArchivedOrDeleted(route: GraphWorkspaceRouteView): boolean {
  return route.lifecycleStatus === 'archived' || route.lifecycleStatus === 'deleted'
}

/** Human-readable route name. Never falls back to a raw id slice: an
 * unlabeled route is described by its branch origin, then by whether it
 * is the Active route. */
function routeLabel(route: GraphWorkspaceRouteView): string {
  if (route.label?.trim()) return route.label.trim()
  if (route.branchType === 'fork') return '分支路线'
  if (route.branchType === 'reanswer') return '重新回答路线'
  if (route.branchType === 'regenerate') return '换题路线'
  return route.isActive ? '主路线' : '路线'
}

/** The card is secondary navigation: reading Focus plus viewport location. */
function openRoute(route: GraphWorkspaceRouteView): void {
  if (graphUi.isRouteHidden(route.id) || !graphUi.lifecycleFilters[route.lifecycleStatus]) {
    return
  }
  graphUi.setFocusRoute(route.id)
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
          { 'route-card--focused': isFocused(route.id) },
        ]"
         :data-route-id="route.id"
         tabindex="0"
         :aria-current="isFocused(route.id) ? 'location' : undefined"
         :aria-label="`${routeLabel(route)}${isFocused(route.id) ? '（正在浏览）' : ''}${route.id === activeRouteId ? '（运行路线）' : ''}`"
         @click="openRoute(route)"
         @keydown.enter.self.prevent="openRoute(route)"
         @keydown.space.self.prevent="openRoute(route)"
      >
        <div class="route-card__primary" data-test="route-primary">
          <div class="route-card__identity">
            <strong class="route-card__label" :title="routeLabel(route)">{{ routeLabel(route) }}</strong>
            <span class="meta-text">{{ route.lineageNodeIds.length }} 个节点</span>
          </div>
          <div class="route-card__state">
            <span v-if="isFocused(route.id)" class="route-focus-indicator" data-test="focus-route-label">
              <span class="route-focus-indicator__dot" aria-hidden="true" />正在浏览
            </span>
            <span v-if="route.id === activeRouteId" class="route-active-indicator" data-test="active-route">
              <span class="route-active-indicator__dot" aria-hidden="true" />运行路线
            </span>
            <span
              v-else-if="route.lifecycleStatus !== 'open'"
              class="badge"
              :class="`badge-${route.lifecycleStatus}`"
            >
              {{ lifecycleLabels[route.lifecycleStatus] }}
            </span>
          </div>
        </div>

        <details class="route-card__more" data-test="route-more" @click.stop>
          <summary class="route-card__more-trigger" aria-label="路线更多操作">···</summary>

          <div class="route-card__menu">
            <div class="route-card__group" data-test="view-actions-group">
              <button class="route-card__menu-item" data-test="locate-route" @click="emit('locate-route', route.id)">定位路线</button>
              <button class="route-card__menu-item" data-test="focus-route" @click="toggleFocus(route)">
                {{ isFocused(route.id) ? '取消浏览聚焦' : '浏览此路线' }}
              </button>
              <button class="route-card__menu-item" data-test="dim-route" @click="setDim(route.id, displayState(route.id) !== 'dimmed')">
                {{ displayState(route.id) === 'dimmed' ? '取消弱化' : '弱化路线' }}
              </button>
              <button class="route-card__menu-item" data-test="hide-route" :disabled="route.id === activeRouteId" @click="setHidden(route.id, displayState(route.id) !== 'hidden')">
                {{ displayState(route.id) === 'hidden' ? '恢复显示' : '隐藏路线' }}
              </button>
              <button class="route-card__menu-item" data-test="isolate-route" @click="isolateRoute(route)">独览此路线</button>
            </div>

            <div class="route-card__group" data-test="runtime-actions-group">
              <button v-if="route.lifecycleStatus === 'open' && !route.isActive" class="route-card__menu-item" data-test="activate-route" :disabled="commandPending" @click="emit('activate', route.id)">设为运行路线</button>
              <button v-if="route.lifecycleStatus !== 'open'" class="route-card__menu-item" data-test="restore-route" :disabled="commandPending" @click="emit('restore', route.id)">恢复路线</button>
              <button v-if="!isArchivedOrDeleted(route)" class="route-card__menu-item" data-test="archive-route" :disabled="commandPending" @click="emit('archive', route.id)">归档</button>
              <button v-if="route.lifecycleStatus !== 'deleted'" class="route-card__menu-item route-card__menu-item--danger" data-test="delete-route" :disabled="commandPending" @click="emit('delete', route.id)">删除路线</button>
            </div>
          </div>
        </details>
      </article>
      <p v-if="routes.length === 0" class="muted">暂无路线。</p>
    </section>
  </div>
</template>
