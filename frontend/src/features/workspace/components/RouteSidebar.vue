<!--
  文件名:RouteSidebar.vue
  用途:画布工作台左侧的路线导航栏,提供路线筛选、定位、只看(单路线镜头)与生命周期操作(设为运行路线 / 恢复 / 归档),只读展示 + 意图上报。
-->
<script setup lang="ts">
import { computed } from 'vue'
import { ROUTE_LIFECYCLE_META, routeDisplayName, routeLifecycleLabel } from '@/features/workspace/presentation/routePresentation'
import type { GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/shared/contracts/types'
import { useGraphUiStore } from '@/features/workspace/state/graphUiStore'
import type { GraphRouteDisplayState } from '@/features/workspace/graph/graphTypes'

/**
 * 本组件只有两个视图概念:定位(viewport)与"只看这条路线"(单路线镜头),
 * 外加运行时生命周期操作(设为运行路线 / 恢复 / 归档并隐藏)。阅读焦点(Focus)
 * 通过点击路线卡片(openRoute)设置,因此不需要单独的"浏览此路线"开关。
 * 独立的"弱化 / 隐藏 / 删除"操作已移除:归档默认隐藏,而逐路线弱化与
 * 生命周期筛选功能重复。
 *
 * "只看这条路线"是一个开关:镜头生效时同一菜单项显示"退出只看",点击另一张
 * 路线卡片会把镜头移过去(绝不静默无操作)。镜头是唯一会隐藏运行路线的视图
 * 状态,且始终携带阅读焦点。
 *
 * 纯视图状态(定位 / 只看 / 筛选)存放在 graphUiStore,绝不触碰运行时状态;
 * 运行时路线操作通过事件抛给工作台外壳。Active 路线不允许手动隐藏,只有
 * 单路线镜头可以隐藏它。
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

/** 焦点路线绝不能指向被生命周期筛选器隐藏的路线。 */
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
 * 路线卡片是二级导航:设置阅读焦点 + 视口定位。
 *
 * 当单路线镜头开启时,其它路线都已移出画布,单纯的"聚焦 + 定位"会指向一条
 * 不可见的路线。因此点击另一张卡片会移动镜头而不是静默无操作;点击当前
 * 被镜头锁定的卡片则保持镜头不变。
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
