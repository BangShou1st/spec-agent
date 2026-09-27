// 文件名:graphTypes.ts
// 用途:画布工作台仅浏览器端的 UI 类型定义:选中/聚焦/弱化/隐藏等视图状态、节点坐标与本地持久化偏好,绝不描述运行时事实、绝不发送到后端。
/*
 * 仅浏览器端的画布工作台 UI 类型。
 *
 * 这些类型只描述视图状态:选中、聚焦、弱化/隐藏、本地节点坐标与侧栏
 * 布局。它们绝不描述运行时事实,也绝不会被发送到后端。
 */

/** 手动设置的逐路线视觉显示状态(仅浏览器端)。 */
export type GraphRouteDisplayState = 'normal' | 'dimmed' | 'hidden'

export interface GraphPosition {
  x: number
  y: number
}

/** 按项目本地持久化的画布布局偏好。 */
export interface ProjectGraphPreferencesV1 {
  version: 1
  nodePositions: Record<string, GraphPosition>
  routeDisplayStates: Record<string, GraphRouteDisplayState>
}

/** V2 展示命名空间:位置以视觉图身份为 key。 */
export interface ProjectGraphPreferencesV2 {
  version: 2
  nodePositions: Record<string, GraphPosition>
  routeDisplayStates: Record<string, GraphRouteDisplayState>
}

/** 全局工作台 UI 偏好,本地持久化。 */
export interface WorkspaceUiPreferencesV1 {
  version: 1
  leftSidebar: { open: boolean; width: number }
  rightSidebar: { open: boolean; width: number }
}

