// 文件名:graph.ts
// 用途:规范项目图的读取 API(Phase 7.3A)。
// 图是只读的"运行历史查看器"投影:节点跨路线去重,路线成员关系是权威数据,
// 各路线自己的回答相互独立。前端绝不重建或改动这份数据。

import { apiClient } from '@/shared/http/client'
import type { GraphWorkspaceView } from '@/shared/contracts/types'

export function getProjectGraph(projectId: string): Promise<GraphWorkspaceView> {
  return apiClient.get<GraphWorkspaceView>(`/projects/${projectId}/graph`)
}
