import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import SkillDetailView from '@/views/settings/SkillDetailView.vue'
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
  enableSkill: vi.fn().mockResolvedValue(undefined),
  disableSkill: vi.fn().mockResolvedValue(undefined),
  deleteSkill: vi.fn().mockResolvedValue(undefined),
}))

const api = vi.mocked(skillsApi)

const detail = { skillId: 's1', name: 'Research', description: 'd', sourceKind: 'BUILTIN', sourceIdentity: 'builtin', versionId: 'v1', enabled: false, createdAt: 'x', updatedAt: 'x' }

function mountView() {
  setActivePinia(createPinia())
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: SkillDetailView }] })
  return mount(SkillDetailView, { props: { skillId: 's1' }, global: { plugins: [router] } })
}

describe('SkillDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getSkill.mockResolvedValue({ ...detail })
    api.listSkillVersions.mockResolvedValue([{ id: 'v1', versionNo: 1, contentHash: 'h', fileCount: 2, totalBytes: 100, createdAt: 'x' }])
    api.listSkillResources.mockResolvedValue([{ path: 'SKILL.md', kind: 'doc', sizeBytes: 50, sha256: 's' }])
  })

  it('renders detail with contextual enable and versions', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-test="skill-detail-name"]').text()).toContain('Research')
    expect(w.get('[data-test="skill-detail-enable"]'))
    expect(w.get('[data-test="skill-version-1"]'))
  })

  it('reads a resource and flags truncation', async () => {
    api.readSkillResource.mockResolvedValue({ relativePath: 'SKILL.md', content: 'body', truncated: true, totalChars: 9000, sha256: 's', versionId: 'v1' })
    const w = mountView()
    await flushPromises()
    await w.get('[data-test="skill-resource-SKILL.md"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-test="resource-truncated"]'))
  })

  it('confirms before delete', async () => {
    const w = mountView()
    await flushPromises()
    await w.get('[data-test="skill-detail-delete"]').trigger('click')
    await w.get('[data-test="skill-detail-delete-confirm"]').trigger('click')
    expect(api.deleteSkill).toHaveBeenCalledWith('s1')
  })
})
