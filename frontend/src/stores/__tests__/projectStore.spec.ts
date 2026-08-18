import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import { useProjectStore } from '@/stores/projectStore'
import { makeProject } from '@/test/fixtures'

vi.mock('@/api/projects', () => ({
  listProjects: vi.fn(),
  createProject: vi.fn(),
}))

import { createProject, listProjects } from '@/api/projects'

const mockedList = vi.mocked(listProjects)
const mockedCreate = vi.mocked(createProject)

describe('projectStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockedList.mockReset()
    mockedCreate.mockReset()
  })

  it('loads projects into state', async () => {
    mockedList.mockResolvedValue([
      makeProject({ id: 'p1', title: 'First' }),
      makeProject({ id: 'p2', title: 'Second' }),
    ])
    const store = useProjectStore()

    await store.loadProjects()

    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
    expect(store.projects).toHaveLength(2)
    expect(store.projects.map((p) => p.title)).toEqual(['First', 'Second'])
  })

  it('surfaces a backend list error safely', async () => {
    mockedList.mockRejectedValue(new ApiError('Project not found', 'PROJECT_NOT_FOUND', 404))
    const store = useProjectStore()

    await store.loadProjects()

    expect(store.loading).toBe(false)
    expect(store.error).toEqual({ code: 'PROJECT_NOT_FOUND', message: 'Project not found' })
  })

  it('creates a project with only a title and appends it to the list', async () => {
    mockedCreate.mockResolvedValue(makeProject({ id: 'p3', title: 'New project', activeRouteId: 'r1' }))
    const store = useProjectStore()

    const created = await store.createProject('New project')

    expect(mockedCreate).toHaveBeenCalledWith('New project')
    expect(created?.id).toBe('p3')
    expect(store.projects.map((p) => p.id)).toContain('p3')
    expect(store.error).toBeNull()
  })

  it('surfaces a backend validation error from creation safely', async () => {
    mockedCreate.mockRejectedValue(
      new ApiError('Request validation failed', 'VALIDATION_ERROR', 400),
    )
    const store = useProjectStore()

    const created = await store.createProject('   ')

    expect(created).toBeNull()
    expect(store.error?.message).toBe('Request validation failed')
    expect(store.creating).toBe(false)
  })
})