import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import SkillsListView from '@/views/settings/SkillsListView.vue'
import * as skillsApi from '@/api/skills'

vi.mock('@/api/skills', () => ({
  listSkills: vi.fn(),
  getSkill: vi.fn(),
  listSkillVersions: vi.fn(),
  listSkillResources: vi.fn(),
  readSkillResource: vi.fn(),
  stageSkillZip: vi.fn(),
  stageSkillGit: vi.fn(),
  listStagedImports: vi.fn(),
  getStagedImport: vi.fn(),
  installStagedImport: vi.fn(),
  rejectStagedImport: vi.fn(),
  deleteStagedImport: vi.fn(),
  enableSkill: vi.fn(),
  disableSkill: vi.fn(),
  deleteSkill: vi.fn(),
}))

const api = vi.mocked(skillsApi)

function mountView() {
  setActivePinia(createPinia())
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: SkillsListView }] })
  return mount(SkillsListView, { global: { plugins: [router] } })
}

describe('SkillsListView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listSkills.mockResolvedValue([])
    api.listStagedImports.mockResolvedValue([])
  })

  it('shows empty state and opens the import dialog', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="skills-empty"]'))
    await w.get('[data-test="add-skill"]').trigger('click')
    expect(w.get('[data-test="import-dialog"]'))
  })

  it('lists installed skills and staged imports', async () => {
    api.listSkills.mockResolvedValue([{ skillId: 's1', name: 'Research', description: 'd', sourceKind: 'BUILTIN', versionId: 'v1', enabled: true, createdAt: 'x' }])
    api.listStagedImports.mockResolvedValue([{ stagedImportId: 'st1', sourceKind: 'UPLOAD_ZIP', sourceIdentity: 'a.zip', manifest: '', totalBytes: 10, fileCount: 1, contentHash: 'h', status: 'STAGED', rejectedReason: null, createdAt: 'x' }])
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="skill-row-s1"]'))
    expect(w.get('[data-test="staged-section"]'))
    expect(w.get('[data-test="review-staged-st1"]'))
  })
})
