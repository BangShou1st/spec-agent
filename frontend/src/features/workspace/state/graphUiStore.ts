// 文件名:graphUiStore.ts
// 用途:画布工作台仅浏览器端的 UI 状态 store(Pinia):选中/多选、Focus 路线、生命周期筛选、弱化/隐藏、展开节点、本地节点位置与侧栏布局,绝不持有运行时事实。
import { defineStore } from 'pinia'
import type { RouteLifecycleStatus } from '@/shared/contracts/types'
import {
  LEFT_SIDEBAR_RANGE,
  RIGHT_SIDEBAR_RANGE,
  loadProjectGraphPreferencesV2,
  loadWorkspaceUiPreferences,
  saveProjectGraphPreferences,
  saveProjectGraphPreferencesV2,
  saveWorkspaceUiPreferences,
} from '@/features/workspace/graph/graphLayoutStorage'
import type {
  GraphPosition,
  GraphRouteDisplayState,
  ProjectGraphPreferencesV1,
  ProjectGraphPreferencesV2,
  WorkspaceUiPreferencesV1,
} from '@/features/workspace/graph/graphTypes'
import { buildVisualInstances } from '@/features/workspace/graph/graphVisualIdentity'

/*
 * 生命周期可见性默认值。
 *
 * `archived` 默认关闭:归档是唯一的"收起这条路线"操作(独立的软删除操作
 * 已移除),因此归档必须真正把路线从默认视图移除。想查看或恢复已归档路线
 * 的用户,在路线侧栏勾选"已归档"筛选即可。
 */
const DEFAULT_FILTERS: Record<RouteLifecycleStatus, boolean> = {
  open: true,
  superseded: true,
  archived: false,
  deleted: false,
}

/*
 * 仅浏览器端的画布工作台 UI 状态。
 *
 * 本 store 拥有选中、多选、Focus 路线、生命周期筛选、弱化/隐藏显示状态、
 * 展开节点、本地节点位置与侧栏布局。它绝不拥有运行时事实:Active 路线、
 * 生命周期、回答、节点、路线与 Spec 内容仍留在 workspaceStore/后端。
 *
 * Focus Route 是唯一的显式浏览器阅读上下文。它绝不改变 Active 路线,
 * 共享节点也绝不从 Active 推断路线。
 *
 * "只看这条路线"是叠加在 Focus 之上的临时单路线镜头:把画布收窄到一条
 * 路线,不触碰 Focus、Active 与持久化的显示状态,并且是唯一可以隐藏
 * Active 路线的视图状态(运行路线始终可以通过"显示全部路线"逃生口找回)。
 *
 * 本地持久化:逐项目的节点位置 + 路线显示状态,以及全局侧栏展开/宽度
 * 偏好。绝不持久化:选中、展开节点、Focus、只看镜头、平移/缩放。
 */
export const useGraphUiStore = defineStore('graphUi', {
  state: () => {
    const workspaceUi = loadWorkspaceUiPreferences()
    return {
      projectId: null as string | null,
      activeRouteId: null as string | null,
      selectedNodeIds: [] as string[],
      primarySelectedNodeId: null as string | null,
      // 一次性的"打开该节点编辑器"请求(例如刚创建一个想法之后)。
      // 匹配的卡片会消费并清掉它。
      pendingEditNodeKey: null as string | null,
      selectedEdgeId: null as string | null,
      selectedSharedEdgeRouteIds: [] as string[],
      focusRouteId: null as string | null,
      /*
       * 临时的"只看这条路线"镜头:画布只渲染这一条路线。
       *
       * 刻意不用 `routeDisplayStates` 建模。那个映射按项目持久化,而
       * Active 路线在其中强制可见(routeVisible)且由 reconcile 修复,因此
       * 通过它隐藏路线永远藏不住运行路线:对非 Active 路线执行"只看这条
       * 路线"时运行路线仍留在画布上——第二次只看看起来像静默无操作。
       *
       * 镜头只在浏览器端存在、绝不持久化:离开镜头(显示全部路线、退出
       * 只看、或点击另一张路线卡片)会精确恢复之前的视图,刷新或切换
       * Active 路线后不会残留隐藏状态。
       */
      isolatedRouteId: null as string | null,
      // 画布拖线(源 handle → 目标 handle)产生的待确认关系提案。在用户于
      // 提案选择器中确认类型与方向之前不持久化任何关系;取消/Esc/点击
      // 空白清掉它,零后端调用。
      pendingRelation: null as {
        sourceNodeId: string
        targetNodeId: string
      } | null,
      // 可选语义关系层的可见性。仅浏览器的临时状态:绝不持久化,默认
      // 恒为 false。Inspector 仍是查看关系的权威场所。
      showRelationLayer: false,
      lifecycleFilters: { ...DEFAULT_FILTERS } as Record<RouteLifecycleStatus, boolean>,
      routeDisplayStates: {} as Record<string, GraphRouteDisplayState>,
      expandedNodeIds: [] as string[],
      nodePositions: {} as Record<string, GraphPosition>,
      leftSidebarOpen: workspaceUi.leftSidebar.open,
      leftSidebarWidth: workspaceUi.leftSidebar.width,
      rightSidebarOpen: workspaceUi.rightSidebar.open,
      rightSidebarWidth: workspaceUi.rightSidebar.width,
    }
  },
  getters: {
    isRouteHidden(state) {
      return (routeId: string): boolean => state.routeDisplayStates[routeId] === 'hidden'
    },
    isRouteDimmed(state) {
      return (routeId: string): boolean => state.routeDisplayStates[routeId] === 'dimmed'
    },
  },
  actions: {
    /*
     * 把仅浏览器的 UI 切换到另一个项目:清空逐项目的视图状态,并加载该
     * 项目已保存的布局。
     */
    initProject(projectId: string): void {
      this.projectId = projectId
      this.selectedNodeIds = []
      this.primarySelectedNodeId = null
      this.focusRouteId = null
      this.isolatedRouteId = null
      this.expandedNodeIds = []
      const v2 = loadProjectGraphPreferencesV2(projectId)
      this.nodePositions = { ...v2.nodePositions }
      this.routeDisplayStates = { ...v2.routeDisplayStates }
    },

    /** 返回显式的浏览器阅读路线,绝不是运行时 Active 路线。 */
    readingRouteId(_activeRouteId?: string | null): string | null {
      return this.focusRouteId
    },

    /** 普通点击:单选替换旧选择。 */
    selectNode(nodeId: string): void {
      this.selectedNodeIds = [nodeId]
      this.primarySelectedNodeId = nodeId
      this.clearEdgeSelection()
    },

    /** 请求匹配的节点卡片打开编辑器,随后自清除。 */
    requestNodeEdit(nodeKey: string): void {
      this.pendingEditNodeKey = nodeKey
    },

    consumeNodeEditRequest(nodeKey: string): boolean {
      if (this.pendingEditNodeKey !== nodeKey) return false
      this.pendingEditNodeKey = null
      return true
    },

    /** Ctrl/Cmd + 点击:加入/移出多选。 */
    toggleSelectNode(nodeId: string): void {
      this.clearEdgeSelection()
      if (this.selectedNodeIds.includes(nodeId)) {
        this.selectedNodeIds = this.selectedNodeIds.filter((id) => id !== nodeId)
        if (this.primarySelectedNodeId === nodeId) {
          this.primarySelectedNodeId = this.selectedNodeIds[0] ?? null
        }
      } else {
        this.selectedNodeIds = [...this.selectedNodeIds, nodeId]
        this.primarySelectedNodeId = nodeId
      }
    },

    /** 把 Vue Flow 的一批选中变化镜像进 UI store。 */
    setSelection(nodeIds: string[]): void {
      this.selectedNodeIds = [...nodeIds]
      if (nodeIds.length > 0) this.clearEdgeSelection()
      if (!this.primarySelectedNodeId || !nodeIds.includes(this.primarySelectedNodeId)) {
        this.primarySelectedNodeId = nodeIds[0] ?? null
      }
    },

    clearSelection(): void {
      this.selectedNodeIds = []
      this.primarySelectedNodeId = null
    },

    selectEdge(edgeId: string, routeIds: string[]): void {
      this.selectedEdgeId = edgeId
      this.selectedSharedEdgeRouteIds = [...routeIds]
      this.clearSelection()
    },

    clearEdgeSelection(): void {
      this.selectedEdgeId = null
      this.selectedSharedEdgeRouteIds = []
    },

    setFocusRoute(routeId: string | null): void {
      // Focus 只是可见路线的阅读上下文:被手动隐藏的路线绝不能成为
      // Focus 路线。
      if (routeId !== null && this.routeDisplayStates[routeId] === 'hidden') {
        return
      }
      this.focusRouteId = routeId
    },

    /*
     * "只看这条路线":在画布上只渲染一条路线。
     *
     * 显式的单路线意图压过所有更弱的视图信号(生命周期筛选、手动弱化/
     * 隐藏、Active),因此镜头也会隐藏运行路线——这正是这条命令的全部
     * 意义。它一键可逆(显示全部路线 / 退出只看),绝不持久化,并且总是
     * 把阅读 Focus 一起带过去:Focus 绝不能指向不可见的路线(够不着的
     * Focus 会把所有可见节点变灰,看起来就像"视图坏了")。
     */
    isolateRoute(routeId: string): void {
      this.isolatedRouteId = routeId
      this.focusRouteId = routeId
    },

    /** 离开只看镜头。Focus 与显示状态都保留。 */
    clearIsolation(): void {
      this.isolatedRouteId = null
    },

    /** 切换画布上可选的语义关系层。 */
    setShowRelationLayer(visible: boolean): void {
      this.showRelationLayer = visible
    },

    /** 把画布拖线登记为待确认关系提案(不调后端)。 */
    setPendingRelation(payload: { sourceNodeId: string; targetNodeId: string } | null): void {
      this.pendingRelation = payload
    },

    clearPendingRelation(): void {
      this.pendingRelation = null
    },

    clearFocusRoute(): void {
      this.focusRouteId = null
    },

    setLifecycleFilter(status: RouteLifecycleStatus, visible: boolean): void {
      this.lifecycleFilters = { ...this.lifecycleFilters, [status]: visible }
    },

    /*
     * 隐藏让路线专属元素离开当前浏览器视图。Active 路线绝不能被隐藏。
     * 隐藏 Focus 路线先清 Focus:Focus 绝不能指向被隐藏的路线。
     */
    hideRoute(routeId: string): void {
      if (routeId === this.activeRouteId) {
        return
      }
      if (routeId === this.focusRouteId) {
        this.focusRouteId = null
      }
      if (routeId === this.isolatedRouteId) {
        this.isolatedRouteId = null
      }
      this.setRouteDisplayState(routeId, 'hidden')
    },

    /** 弱化让路线保持可见但降低视觉权重。 */
    dimRoute(routeId: string): void {
      this.setRouteDisplayState(routeId, 'dimmed')
    },

    restoreRouteDisplay(routeId: string): void {
      this.setRouteDisplayState(routeId, 'normal')
    },

    setRouteDisplayState(routeId: string, state: GraphRouteDisplayState): void {
      this.routeDisplayStates = { ...this.routeDisplayStates, [routeId]: state }
      this.persistProjectState()
    },

    /*
     * 清除只看镜头与手动弱化/隐藏,但保留 Focus 与生命周期筛选。这是
     * 权威的"回到全视图"逃生口。
     */
    showAll(): void {
      this.isolatedRouteId = null
      this.routeDisplayStates = {}
      this.persistProjectState()
    },

    /*
     * 恢复完整默认视图:清掉 Focus、只看镜头、手动显示状态,并把生命周期
     * 筛选重置为默认值。
     */
    resetView(): void {
      this.focusRouteId = null
      this.isolatedRouteId = null
      this.routeDisplayStates = {}
      this.lifecycleFilters = { ...DEFAULT_FILTERS }
      this.persistProjectState()
    },

    toggleExpanded(nodeId: string): void {
      this.expandedNodeIds = this.expandedNodeIds.includes(nodeId)
        ? this.expandedNodeIds.filter((id) => id !== nodeId)
        : [...this.expandedNodeIds, nodeId]
    },

    setNodePosition(nodeId: string, position: GraphPosition): void {
      this.nodePositions = { ...this.nodePositions, [nodeId]: position }
      this.persistProjectState()
    },

    /** 持久化一批节点位置(拖拽结束时使用)。 */
    setNodePositions(positions: Record<string, GraphPosition>): void {
      this.nodePositions = { ...this.nodePositions, ...positions }
      this.persistProjectState()
    },

    setLeftSidebar(prefs: { open: boolean; width: number }): void {
      this.leftSidebarOpen = prefs.open
      this.leftSidebarWidth = clampSidebarWidth(prefs.width, LEFT_SIDEBAR_RANGE)
      this.persistWorkspaceState()
    },

    setRightSidebar(prefs: { open: boolean; width: number }): void {
      this.rightSidebarOpen = prefs.open
      this.rightSidebarWidth = clampSidebarWidth(prefs.width, RIGHT_SIDEBAR_RANGE)
      this.persistWorkspaceState()
    },

    /*
     * 每次刷新后,把仅浏览器的视图状态与 canonical 图对账:丢弃过期的
     * 选中,路线消失时清掉只看镜头,不再可见的路线上清掉 Focus,并修复
     * Active 路线上任何被持久化的隐藏状态。
     */
    reconcile(view: {
      activeRouteId: string | null
      routes: { id: string; lifecycleStatus: RouteLifecycleStatus }[]
      nodes: { id: string }[]
    } | null): void {
      if (!view) {
        return
      }
      this.activeRouteId = view.activeRouteId
      const nodeIds = new Set(view.nodes.map((node) => node.id))
      if ('routes' in view && 'nodes' in view) {
        for (const instance of buildVisualInstances(view as Parameters<typeof buildVisualInstances>[0])) {
          nodeIds.add(instance.visualNodeKey)
        }
      }
      this.selectedNodeIds = this.selectedNodeIds.filter((id) => nodeIds.has(id))
      if (
        this.primarySelectedNodeId &&
        !nodeIds.has(this.primarySelectedNodeId)
      ) {
        this.primarySelectedNodeId = this.selectedNodeIds[0] ?? null
      }

      // 只看镜头是显式的单路线意图:它在生命周期筛选变化与下方的
      // Active 路线修复中幸存。只有路线本身从工作区消失时才丢弃。
      if (
        this.isolatedRouteId &&
        !view.routes.some((route) => route.id === this.isolatedRouteId)
      ) {
        this.isolatedRouteId = null
      }

      if (this.focusRouteId) {
        const focusRoute = view.routes.find((route) => route.id === this.focusRouteId)
        const keptVisibleByLens =
          this.isolatedRouteId !== null && this.isolatedRouteId === this.focusRouteId
        const visible =
          focusRoute !== undefined &&
          (keptVisibleByLens ||
            (this.lifecycleFilters[focusRoute.lifecycleStatus] === true &&
              this.routeDisplayStates[focusRoute.id] !== 'hidden'))
        if (!visible) {
          this.focusRouteId = null
        }
      }

      if (
        view.activeRouteId &&
        this.routeDisplayStates[view.activeRouteId] === 'hidden'
      ) {
        // 被持久化的隐藏状态绝不能隐藏 Active 路线;修复为 normal,
        // 修复结果同样持久化。
        this.routeDisplayStates = {
          ...this.routeDisplayStates,
          [view.activeRouteId]: 'normal',
        }
        this.persistProjectState()
      }
    },

    persistProjectState(): void {
      if (!this.projectId) {
        return
      }
      const prefs: ProjectGraphPreferencesV1 = {
        version: 1,
        nodePositions: { ...this.nodePositions },
        routeDisplayStates: { ...this.routeDisplayStates },
      }
      saveProjectGraphPreferences(this.projectId, prefs)
      const prefsV2: ProjectGraphPreferencesV2 = {
        version: 2,
        nodePositions: { ...this.nodePositions },
        routeDisplayStates: { ...this.routeDisplayStates },
      }
      saveProjectGraphPreferencesV2(this.projectId, prefsV2)
    },

    persistWorkspaceState(): void {
      const prefs: WorkspaceUiPreferencesV1 = {
        version: 1,
        leftSidebar: { open: this.leftSidebarOpen, width: this.leftSidebarWidth },
        rightSidebar: { open: this.rightSidebarOpen, width: this.rightSidebarWidth },
      }
      saveWorkspaceUiPreferences(prefs)
    },
  },
})

function clampSidebarWidth(
  width: number,
  range: { min: number; max: number; default: number },
): number {
  if (!Number.isFinite(width)) return range.default
  return Math.min(range.max, Math.max(range.min, width))
}
