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
  renameProject: vi.fn(),
}))

import { createProject, listProjects, renameProject } from '@/api/projects'

const mockedList = vi.mocked(listProjects)
const mockedCreate = vi.mocked(createProject)
const mockedRename = vi.mocked(renameProject)

type ProjectSummaryList = Awaited<ReturnType<typeof listProjects>>

// The backend is the filter now: the mock returns the server-filtered subset
// for a given title, exactly like GET /projects?title= would.
function mockListByQuery(all: ProjectSummaryList): void {
  mockedList.mockImplementation((title?: string) =>
    Promise.resolve(
      !title ? all : all.filter((p) => p.title.toLowerCase().includes(title.toLowerCase())),
    ),
  )
}

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
    mockListByQuery([
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

    expect(wrapper.text()).toContain('还没有项目')
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

  it('asks the backend to filter by title and shows the matched subset', async () => {
    mockListByQuery([
      makeProjectSummary({ id: 'p1', title: '结算规格梳理' }),
      makeProjectSummary({ id: 'p2', title: '登录流程优化' }),
    ])
    const { wrapper } = await mountProjects()

    await wrapper.find('[data-test="project-search"]').setValue('结算')

    expect(wrapper.text()).toContain('结算规格梳理')
    expect(wrapper.text()).not.toContain('登录流程优化')
  })

  it('highlights the matched part of the title without altering the text', async () => {
    mockListByQuery([makeProjectSummary({ id: 'p1', title: '结算规格梳理' })])
    const { wrapper } = await mountProjects()

    await wrapper.find('[data-test="project-search"]').setValue('规格')

    const marks = wrapper.findAll('[data-test="project-title-match"]')
    expect(marks.map((mark) => mark.text())).toEqual(['规格'])
    // The title is split for highlighting, but still renders as one string.
    expect(wrapper.find('.project-row__title').text()).toBe('结算规格梳理')
  })

  it('reports the match count while a search is active', async () => {
    mockListByQuery([
      makeProjectSummary({ id: 'p1', title: '结算规格梳理' }),
      makeProjectSummary({ id: 'p2', title: '登录流程优化' }),
    ])
    const { wrapper } = await mountProjects()

    expect(wrapper.find('[data-test="project-search-count"]').exists()).toBe(false)
    await wrapper.find('[data-test="project-search"]').setValue('流程')
    expect(wrapper.find('[data-test="project-search-count"]').text()).toContain('1')
  })

  it('shows a no-match state and clears the search from it', async () => {
    mockListByQuery([
      makeProjectSummary({ id: 'p1', title: '结算规格梳理' }),
      makeProjectSummary({ id: 'p2', title: '登录流程优化' }),
    ])
    const { wrapper } = await mountProjects()

    await wrapper.find('[data-test="project-search"]').setValue('不存在的关键词')
    expect(wrapper.find('[data-test="project-search-empty"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('结算规格梳理')

    await wrapper.find('[data-test="project-search-reset"]').trigger('click')

    expect(wrapper.find('[data-test="project-search-empty"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('结算规格梳理')
    expect(wrapper.text()).toContain('登录流程优化')
  })

  it('clears the search from the toolbar button', async () => {
    mockListByQuery([makeProjectSummary({ id: 'p1', title: '结算规格梳理' })])
    const { wrapper } = await mountProjects()

    await wrapper.find('[data-test="project-search"]').setValue('zzz')
    await wrapper.find('[data-test="project-search-clear"]').trigger('click')

    expect(wrapper.text()).toContain('结算规格梳理')
  })

  it('sends the query to the backend and reflects the filtered result', async () => {
    mockListByQuery([
      makeProjectSummary({ id: 'p1', title: '结算规格梳理' }),
      makeProjectSummary({ id: 'p2', title: '登录流程优化' }),
    ])
    const { wrapper } = await mountProjects()

    await wrapper.find('[data-test="project-search"]').setValue('登录')

    // The term is forwarded to the API; the backend decides what matches.
    expect(mockedList).toHaveBeenCalledWith('登录')
    expect(wrapper.text()).not.toContain('结算规格梳理')
    expect(wrapper.text()).toContain('登录流程优化')
  })

  it('hides the search box when there are no projects at all', async () => {
    mockedList.mockResolvedValue([])
    const { wrapper } = await mountProjects()

    expect(wrapper.find('[data-test="project-search"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('还没有项目')
  })

  it('shows a safe backend error banner when listing fails', async () => {
    mockedList.mockRejectedValue(new ApiError('An unexpected internal error occurred', 'INTERNAL_ERROR', 500))
    const { wrapper } = await mountProjects()

    expect(wrapper.text()).toContain('INTERNAL_ERROR')
    expect(wrapper.text()).toContain('An unexpected internal error occurred')
    expect(wrapper.text()).not.toContain('stack trace')
  })

  it('renames a project inline in its own row and reflects the new title', async () => {
    mockListByQuery([makeProjectSummary({ id: 'p1', title: '旧名称' })])
    mockedRename.mockResolvedValue({
      id: 'p1', title: '新名称', activeRouteId: 'r1', defaultProfileId: null,
      createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z',
    })
    const { wrapper } = await mountProjects()

    await wrapper.get('[data-test="project-menu-p1"]').trigger('click')
    await wrapper.get('[data-test="rename-project-p1"]').trigger('click')

    // 行内编辑：输入框就在这一行里，不再有底部弹窗。
    const inline = wrapper.get('[data-test="rename-project-inline"]')
    expect(wrapper.find('[data-test="rename-project-dialog"]').exists()).toBe(false)
    // 编辑态下这一行的标题链接让位给输入框。
    expect(inline.find('[data-test="rename-project-input"]').exists()).toBe(true)

    await wrapper.get('[data-test="rename-project-input"]').setValue('新名称')
    await wrapper.get('[data-test="rename-project-confirm"]').trigger('click')
    await flushPromises()

    expect(mockedRename).toHaveBeenCalledWith('p1', '新名称')
    expect(wrapper.text()).toContain('新名称')
    expect(wrapper.find('[data-test="rename-project-inline"]').exists()).toBe(false)
  })

  it('cancels the inline rename without calling the API', async () => {
    mockListByQuery([makeProjectSummary({ id: 'p1', title: '旧名称' })])
    const { wrapper } = await mountProjects()

    await wrapper.get('[data-test="project-menu-p1"]').trigger('click')
    await wrapper.get('[data-test="rename-project-p1"]').trigger('click')
    await wrapper.get('[data-test="rename-project-cancel"]').trigger('click')

    expect(mockedRename).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="rename-project-inline"]').exists()).toBe(false)
    // 退出编辑态后标题链接回来。
    expect(wrapper.get('.project-row__title').text()).toContain('旧名称')
  })
})
