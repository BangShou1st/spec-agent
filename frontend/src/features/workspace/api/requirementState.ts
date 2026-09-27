// 文件名:requirementState.ts
// 用途:需求状态的读取 API:按项目(激活路线)的旧读取,以及
//       按显式路线的路线级读取(Phase 7.3A)。

import { apiClient } from '@/shared/http/client'
import type { RequirementStateView } from '@/shared/contracts/types'

/** 旧版按激活路线的需求状态读取(保持不变)。 */
export function getRequirementState(projectId: string): Promise<RequirementStateView> {
  return apiClient.get<RequirementStateView>(`/projects/${projectId}/requirement-state`)
}

/** 按显式路线的路线级需求状态读取(Phase 7.3A)。 */
export function getRouteRequirementState(
  projectId: string,
  routeId: string,
): Promise<RequirementStateView> {
  return apiClient.get<RequirementStateView>(
    `/projects/${projectId}/routes/${routeId}/requirement-state`,
  )
}
