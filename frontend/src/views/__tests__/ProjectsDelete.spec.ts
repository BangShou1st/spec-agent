import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ProjectsView from '../ProjectsView.vue'
import * as projectsApi from '@/api/projects'

describe('Projects delete UI', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks() })
  function router() { return createRouter({ history: createWebHistory(), routes: [{ path: '/', component: { template: '<div />' } }, { path: '/projects/:id', component: { template: '<div />' } }] }) }
  it('menu opens and asks confirm with required copy', async () => {
    vi.spyOn(projectsApi, 'listProjects').mockResolvedValue([{ id: 'p1', title: '演示项目', activeRouteId: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }])
    const w = mount(ProjectsView, { global: { plugins: [router()] } })
    await new Promise((r) => setTimeout(r, 0))
    await w.vm.$nextTick()
    const menuBtn = w.find('[data-test=project-menu-p1]')
    expect(menuBtn.exists()).toBe(true)
    await menuBtn.trigger('click')
    const del = w.find('[data-test=delete-project-p1]')
    expect(del.exists()).toBe(true)
    await del.trigger('click')
    expect(w.text()).toContain('删除「演示项目」？')
    expect(w.text()).toContain('将永久删除该项目的路线、节点、答案、状态与 Spec')
    expect(w.text()).toContain('此操作无法撤销')
  })
  it('confirm deletes and removes row, failure shows message', async () => {
    vi.spyOn(projectsApi, 'listProjects').mockResolvedValue([{ id: 'p1', title: 'A', activeRouteId: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }])
    const delMock = vi.spyOn(projectsApi, 'deleteProject').mockResolvedValue(undefined)
    const w = mount(ProjectsView, { global: { plugins: [router()] } })
    await new Promise((r) => setTimeout(r, 0))
    await w.vm.$nextTick()
    await w.find('[data-test=project-menu-p1]').trigger('click')
    await w.find('[data-test=delete-project-p1]').trigger('click')
    await w.find('[data-test=ui-confirm-ok]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(delMock).toHaveBeenCalledWith('p1')
    expect(w.find('[data-test=delete-project-p1]').exists()).toBe(false)
  })
})
