<script setup lang="ts">
import { computed } from 'vue'
import type { GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/api/types'
import type { GraphRouteDisplayState } from '@/graph/graphTypes'
import { useGraphUiStore } from '@/stores/graphUiStore'

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
  open: '开放', superseded: '已替代', archived: '已归档', deleted: '已删除',
}
const filters: { status: RouteLifecycleStatus; label: string }[] = [
  { status: 'open', label: '开放' },
  { status: 'superseded', label: '已替代' },
  { status: 'archived', label: '已归档' },
  { status: 'deleted', label: '已删除' },
]
const sortedRoutes = computed(() => props.routes)
const displayState = (id: string): GraphRouteDisplayState => graphUi.routeDisplayStates[id] ?? 'normal'
const focused = (id: string): boolean => graphUi.focusRouteId === id
const branchLabel = (route: GraphWorkspaceRouteView): string | null => {
  if (route.branchType === 'fork') return 'Fork'
  if (route.branchType === 'reanswer') return 'Re-answer'
  if (route.branchType === 'regenerate') return '替代问题'
  return null
}

function setFilter(status: RouteLifecycleStatus, visible: boolean): void {
  if (!visible && props.routes.find((route) => route.id === graphUi.focusRouteId)?.lifecycleStatus === status) graphUi.clearFocusRoute()
  graphUi.setLifecycleFilter(status, visible)
}

function openRoute(route: GraphWorkspaceRouteView): void {
  if (displayState(route.id) === 'hidden' || !graphUi.lifecycleFilters[route.lifecycleStatus]) return
  graphUi.setFocusRoute(route.id)
  emit('locate-route', route.id)
}
</script>

<template>
  <div class="route-navigator" data-test="route-navigator">
    <span class="workspace-shell__compat-marker" data-test="route-sidebar" aria-hidden="true"></span>
    <section class="route-navigator__filters" data-test="lifecycle-filters">
      <h3 class="route-navigator__heading">生命周期</h3>
      <label v-for="filter in filters" :key="filter.status" class="route-navigator__filter">
        <input type="checkbox" :checked="graphUi.lifecycleFilters[filter.status]" :data-test="`filter-${filter.status}`" @change="setFilter(filter.status, ($event.target as HTMLInputElement).checked)" />
        {{ filter.label }}
      </label>
    </section>

    <section data-test="route-list">
      <h3 class="route-navigator__heading">路线</h3>
      <article v-for="route in sortedRoutes" :key="route.id" class="route-navigator__route" :class="[`route-card--${displayState(route.id)}`, { 'route-navigator__route--focused': focused(route.id), 'route-card--focused': focused(route.id) }]" :data-route-id="route.id" @click="openRoute(route)">
        <div class="route-navigator__route-head">
          <span class="badge" :class="`badge-${route.lifecycleStatus}`">{{ lifecycleLabels[route.lifecycleStatus] }}</span>
          <span v-if="route.id === activeRouteId" class="badge badge-active" data-test="active-route">Active</span>
          <span v-if="focused(route.id)" class="badge badge-focus">Focus</span>
        </div>
        <div class="route-navigator__label">{{ route.label ?? route.id.slice(0, 8) }}</div>
        <div class="meta-text">{{ route.lineageNodeIds.length }} 个节点</div>
        <div v-if="branchLabel(route)" class="meta-text">来源：{{ branchLabel(route) }}<span v-if="route.branchAtNodeId"> · {{ route.branchAtNodeId.slice(0, 8) }}</span></div>
        <div class="route-navigator__actions" @click.stop>
          <button class="btn btn-small" data-test="locate-route" @click="emit('locate-route', route.id)">定位路线</button>
          <button class="btn btn-small" data-test="focus-route" @click="focused(route.id) ? graphUi.clearFocusRoute() : graphUi.setFocusRoute(route.id)">{{ focused(route.id) ? '取消聚焦' : '聚焦此路线' }}</button>
          <button class="btn btn-small" data-test="dim-route" @click="displayState(route.id) === 'dimmed' ? graphUi.restoreRouteDisplay(route.id) : graphUi.dimRoute(route.id)">{{ displayState(route.id) === 'dimmed' ? '取消弱化' : '弱化路线' }}</button>
          <button class="btn btn-small" data-test="hide-route" :disabled="route.id === activeRouteId" @click="displayState(route.id) === 'hidden' ? graphUi.restoreRouteDisplay(route.id) : graphUi.hideRoute(route.id)">{{ displayState(route.id) === 'hidden' ? '恢复显示' : '隐藏路线' }}</button>
        </div>
        <div class="route-navigator__actions" @click.stop>
          <button v-if="route.lifecycleStatus === 'open' && !route.isActive" class="btn btn-small" data-test="activate-route" :disabled="commandPending" @click="emit('activate', route.id)">Activate</button>
          <button v-if="route.lifecycleStatus !== 'open'" class="btn btn-small" data-test="restore-route" :disabled="commandPending" @click="emit('restore', route.id)">Restore</button>
          <button v-if="route.lifecycleStatus !== 'archived' && route.lifecycleStatus !== 'deleted'" class="btn btn-small" data-test="archive-route" :disabled="commandPending" @click="emit('archive', route.id)">Archive</button>
          <button v-if="route.lifecycleStatus !== 'deleted'" class="btn btn-small btn-danger" data-test="delete-route" :disabled="commandPending" @click="emit('delete', route.id)">Delete</button>
        </div>
      </article>
      <p v-if="routes.length === 0" class="muted">暂无路线。</p>
    </section>
  </div>
</template>
