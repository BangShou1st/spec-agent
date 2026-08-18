import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import { ApiError } from '@/api/client'
import { makeProjectSummary } from '@/test/fixtures'

vi.mock('@/api/projects', () => ({
  listProjects: vi.fn(),
  createProject: vi.fn(),
}))

import { createProject, listProjects } from '@/api/projects'

const mockedList = vi.mocked(listProjects)
const mockedCreate = vi.mocked(createProject)

async function mountProjects() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects', component: ProjectsView },
      { path: '/projects/:projectId', component: { template: '<div>workspace stub</div>' } },
    ],
  })
  router.push('/projects')
  await router.isReady()
  const wrapper = mount(ProjectsView, {
    global: { plugins: [createPinia(), router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ProjectsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('lists existing projects from the backend', async () => {
    mockedList.mockResolvedValue([
      makeProjectSummary({ id: 'p1', title: 'First project' }),
      makeProjectSummary({ id: 'p2', title: 'Second project' }),
    ])
    const { wrapper } = await mountProjects()

    expect(mockedList).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('First project')
    expect(wrapper.text()).toContain('Second project')
  })

  it('shows an empty state when no projects exist', async () => {
    mockedList.mockResolvedValue([])
    const { wrapper } = await mountProjects()

    expect(wrapper.text()).toContain('No projects yet.')
  })

  it('navigates to the workspace after creating a project', async () => {
    mockedList.mockResolvedValue([])
    mockedCreate.mockResolvedValue({
      id: 'p-new',
      title: 'New requirement',
      activeRouteId: 'r1',
      defaultProfileId: 'profile-1',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    })
    const { wrapper, router } = await mountProjects()

    await wrapper.find('input[aria-label="Project title"]').setValue('New requirement')
    await wrapper.find('button[type="submit"]').trigger('submit')
    await flushPromises()

    expect(mockedCreate).toHaveBeenCalledWith('New requirement')
    expect(router.currentRoute.value.path).toBe('/projects/p-new')
  })

  it('does not submit a blank title client-side and keeps the backend authoritative', async () => {
    mockedList.mockResolvedValue([])
    const { wrapper } = await mountProjects()

    const input = wrapper.find('input[aria-label="Project title"]')
    await input.setValue('   ')
    const button = wrapper.find('button[type="submit"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('submit')
    expect(mockedCreate).not.toHaveBeenCalled()
  })

  it('shows a safe backend error banner when listing fails', async () => {
    mockedList.mockRejectedValue(new ApiError('An unexpected internal error occurred', 'INTERNAL_ERROR', 500))
    const { wrapper } = await mountProjects()

    expect(wrapper.text()).toContain('INTERNAL_ERROR')
    expect(wrapper.text()).toContain('An unexpected internal error occurred')
    expect(wrapper.text()).not.toContain('stack trace')
  })
})