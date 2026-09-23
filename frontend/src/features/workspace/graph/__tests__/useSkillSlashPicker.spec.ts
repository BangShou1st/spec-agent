import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSkillSlashPicker } from '@/features/workspace/graph/useSkillSlashPicker'
import { useSkillsStore } from '@/features/skills/state/skillsStore'
import * as skillsApi from '@/features/skills/api/skills'
import type { SkillSummary } from '@/features/skills/api/skillTypes'

vi.mock('@/features/skills/api/skills', () => ({
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

const BRAINSTORM: SkillSummary = {
  skillId: 'brainstorm',
  name: 'brainstorming',
  description: '把模糊想法澄清成需求',
  sourceKind: 'GIT_HTTPS',
  versionId: 'v1',
  enabled: true,
  createdAt: '2026-09-01T00:00:00Z',
}
const DISABLED: SkillSummary = {
  ...BRAINSTORM,
  skillId: 'off-skill',
  name: 'disabled-skill',
  enabled: false,
}

function pickerWith(skills: SkillSummary[]) {
  const store = useSkillsStore()
  store.list = skills
  return useSkillSlashPicker()
}

describe('useSkillSlashPicker', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.listSkills.mockResolvedValue([])
  })

  it('opens on a slash token before the caret and filters by query', () => {
    const picker = pickerWith([BRAINSTORM, DISABLED])
    expect(picker.syncWithCaret('写个想法 /br', 8)).toEqual({ start: 5, query: 'br' })
    expect(picker.open.value).toBe(true)
    // Disabled skills never become candidates.
    expect(picker.filtered.value.map((skill) => skill.skillId)).toEqual(['brainstorm'])
  })

  it('does not open when the slash is mid-word', () => {
    const picker = pickerWith([BRAINSTORM])
    expect(picker.syncWithCaret('abc/def', 7)).toBeNull()
    expect(picker.open.value).toBe(false)
  })

  it('closes when the token is deleted', () => {
    const picker = pickerWith([BRAINSTORM])
    picker.syncWithCaret('/br', 3)
    expect(picker.open.value).toBe(true)
    picker.syncWithCaret('', 0)
    expect(picker.open.value).toBe(false)
  })

  it('replaces the token with a mention and moves the caret past it', () => {
    const picker = pickerWith([BRAINSTORM])
    const text = '想法 /bra 继续写'
    // Caret sits right after "/bra" (index 7), before the trailing space.
    picker.syncWithCaret(text, 7)
    const applied = picker.applySkill(text, 7, BRAINSTORM)
    expect(applied.text).toBe('想法 @skill/brainstorming 继续写')
    // Caret lands right after the mention, before the pre-existing space.
    expect(applied.caret).toBe('想法 @skill/brainstorming'.length)
  })

  it('keyboard navigation wraps and selects the active skill', () => {
    const second = { ...BRAINSTORM, skillId: 'td', name: 'tdd' }
    const picker = pickerWith([BRAINSTORM, second])
    picker.syncWithCaret('/', 1)
    expect(picker.activeSkill()?.skillId).toBe('brainstorm')
    picker.move(1)
    expect(picker.activeSkill()?.skillId).toBe('td')
    picker.move(1)
    expect(picker.activeSkill()?.skillId).toBe('brainstorm')
  })
})
