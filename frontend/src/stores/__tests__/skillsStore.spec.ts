import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSkillsStore } from '@/stores/skillsStore'
import { ApiError } from '@/api/client'
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

describe('skillsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.listSkills.mockResolvedValue([])
    api.listStagedImports.mockResolvedValue([])
  })

  it('loads installed list', async () => {
    api.listSkills.mockResolvedValue([{ skillId: 's1' }] as never)
    const store = useSkillsStore()
    await store.loadList()
    expect(store.list).toEqual([{ skillId: 's1' }])
    expect(store.error).toBeNull()
  })

  it('loads detail with versions and resources', async () => {
    api.getSkill.mockResolvedValue({ skillId: 's1' } as never)
    api.listSkillVersions.mockResolvedValue([{ id: 'v1' }] as never)
    api.listSkillResources.mockResolvedValue([{ path: 'SKILL.md' }] as never)
    const store = useSkillsStore()
    await store.loadDetail('s1')
    expect(store.detail?.skillId).toBe('s1')
    expect(store.versions).toHaveLength(1)
    expect(store.resources).toHaveLength(1)
  })

  it('maps typed errors', async () => {
    api.listSkills.mockRejectedValue(new ApiError('bad', 'SKILL_IMPORT_REJECTED', 400))
    const store = useSkillsStore()
    await store.loadList()
    expect(store.error?.code).toBe('SKILL_IMPORT_REJECTED')
  })
})
