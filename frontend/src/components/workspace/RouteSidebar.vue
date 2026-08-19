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

function isArchivedOrDeleted(route: GraphWorkspaceRouteView): boolean {
  return route.lifecycleStatus === 'archived' || route.lifecycleStatus === 'deleted'
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
    <section class="route-sidebar__section" data-test="lifecycle-filters">
      <h3 class="route-sidebar__heading">生命周期筛选</h3>
      <label v-for="filter in filterOptions" :key="filter.status" class="route-sidebar__filter">
        <input
          type="checkbox"
          :checked="graphUi.lifecycleFilters[filter.status]"
          :data-test="`filter-${filter.status}`"
          @change="setFilter(filter.status, ($event.target as HTMLInputElement).checked)"
        />
        {{ filter.label }}
      </label>
    </section>

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
         @click="openRoute(route)"
      >
        <div class="route-card__head">
          <span class="badge" :class="`badge-${route.lifecycleStatus}`">
            {{ lifecycleLabels[route.lifecycleStatus] }}
          </span>
          <span v-if="route.id === activeRouteId" class="badge badge-active" data-test="active-route">
            当前路线
          </span>
        </div>
        <div class="route-card__label">{{ route.label ?? route.id.slice(0, 8) }}</div>
        <div class="meta-text">节点数：{{ route.lineageNodeIds.length }}</div>

        <details class="route-card__more" open>
          <summary>更多</summary>
        <div class="route-card__group" data-test="view-actions-group">
          <span class="route-card__group-title">查看</span>
          <div class="route-card__actions">
             <button class="btn btn-small" data-test="locate-route" @click.stop="emit('locate-route', route.id)">定位路线</button>
            <button
              class="btn btn-small"
              data-test="focus-route"
              :class="{ 'route-card__focused-btn': isFocused(route.id) }"
               @click.stop="toggleFocus(route)"
            >
              {{ isFocused(route.id) ? '取消聚焦' : '聚焦此路线' }}
            </button>
            <button
              class="btn btn-small"
              data-test="dim-route"
              :class="{ 'route-card__dimmed-btn': displayState(route.id) === 'dimmed' }"
               @click.stop="setDim(route.id, displayState(route.id) !== 'dimmed')"
            >
              {{ displayState(route.id) === 'dimmed' ? '取消弱化' : '弱化路线' }}
            </button>
            <button
              class="btn btn-small"
              data-test="hide-route"
              :disabled="route.id === activeRouteId"
               @click.stop="setHidden(route.id, displayState(route.id) !== 'hidden')"
            >
              {{ displayState(route.id) === 'hidden' ? '恢复显示' : '隐藏路线' }}
            </button>
          </div>
        </div>

        <div class="route-card__group" data-test="runtime-actions-group">
          <span class="route-card__group-title">路线状态</span>
          <div class="route-card__actions">
            <button
              v-if="route.lifecycleStatus === 'open' && !route.isActive"
              class="btn btn-small"
              data-test="activate-route"
              :disabled="commandPending"
               @click.stop="emit('activate', route.id)"
            >
              设为当前路线
            </button>
            <button
              v-if="route.lifecycleStatus !== 'open'"
              class="btn btn-small"
              data-test="restore-route"
              :disabled="commandPending"
               @click.stop="emit('restore', route.id)"
            >
              恢复
            </button>
            <button
              v-if="!isArchivedOrDeleted(route)"
              class="btn btn-small"
              data-test="archive-route"
              :disabled="commandPending"
               @click.stop="emit('archive', route.id)"
            >
              归档
            </button>
            <button
              v-if="route.lifecycleStatus !== 'deleted'"
              class="btn btn-small btn-danger"
              data-test="delete-route"
              :disabled="commandPending"
               @click.stop="emit('delete', route.id)"
            >
              删除路线
            </button>
          </div>
        </div>
        </details>
      </article>
      <p v-if="routes.length === 0" class="muted">暂无路线。</p>
    </section>
  </div>
</template>
