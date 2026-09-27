// 文件名:graph.spec.ts
// 用途:规范项目图读取 API 单测:验证 getProjectGraph 的端点 URL 与透传行为。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/http/client'
import { getProjectGraph } from '@/features/workspace/api/graph'
import type { GraphWorkspaceView } from '@/shared/contracts/types'

vi.mock('@/shared/http/client', () => ({
  apiClient: { get: vi.fn() },
}))

const mockedGet = vi.mocked(apiClient.get)

const emptyGraph: GraphWorkspaceView = {
  projectId: 'p1',
  activeRouteId: null,
  routes: [],
  nodes: [],
  answers: [],
  relations: [],
}

describe('graph api', () => {
  beforeEach(() => {
    mockedGet.mockReset()
  })

  it('fetches the canonical project graph view', async () => {
    mockedGet.mockResolvedValue(emptyGraph)

    const view = await getProjectGraph('p1')

    expect(mockedGet).toHaveBeenCalledWith('/projects/p1/graph')
    expect(view.projectId).toBe('p1')
  })

  it('passes every project id through to the exact path', async () => {
    mockedGet.mockResolvedValue(emptyGraph)

    await getProjectGraph('abc-123')

    expect(mockedGet).toHaveBeenCalledWith('/projects/abc-123/graph')
  })
})
