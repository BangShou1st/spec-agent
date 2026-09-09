import { afterEach, describe, expect, it, vi } from 'vitest'
import { deleteSkill, deleteStagedImport, disableSkill, enableSkill, getSkill, installStagedImport, listSkills, listSkillVersions, readSkillResource, rejectStagedImport, stageSkillGit } from '@/api/skills'

function ok(body: unknown, status = 200) {
  return { ok: true, status, text: async () => (body == null ? '' : JSON.stringify(body)), json: async () => body }
}

describe('skills api', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists skills and reads detail, versions, and resources', async () => {
    const { listSkillResources } = await import('@/api/skills')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok([{ skillId: 's1' }]))
      .mockResolvedValueOnce(ok({ skillId: 's1' }))
      .mockResolvedValueOnce(ok([{ id: 'v1' }]))
      .mockResolvedValueOnce(ok([{ path: 'SKILL.md' }]))
      .mockResolvedValueOnce(ok({ relativePath: 'SKILL.md', content: 'x' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(listSkills()).resolves.toEqual([{ skillId: 's1' }])
    await expect(getSkill('s1')).resolves.toMatchObject({ skillId: 's1' })
    await expect(listSkillVersions('s1')).resolves.toEqual([{ id: 'v1' }])
    await expect(listSkillResources('s1')).resolves.toEqual([{ path: 'SKILL.md' }])
    await expect(readSkillResource('s1', 'SKILL.md')).resolves.toMatchObject({ relativePath: 'SKILL.md' })
  })

  it('drives staged git import, install, reject, and lifecycle without FormData JSON headers', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(ok({ stagedImportId: 'st1' }, 201))
      .mockResolvedValueOnce(ok({ skillId: 's9' }))
      .mockResolvedValueOnce(ok(undefined, 204))
      .mockResolvedValueOnce(ok(undefined, 204))
      .mockResolvedValueOnce(ok(undefined, 204))
      .mockResolvedValueOnce(ok(undefined, 204))
      .mockResolvedValueOnce(ok(undefined, 204))
    vi.stubGlobal('fetch', fetchMock)
    await stageSkillGit('https://example.com/skill.git', 'main')
    await installStagedImport('st1')
    await rejectStagedImport('st1', 'nope')
    await deleteStagedImport('st1')
    await enableSkill('s9')
    await disableSkill('s9')
    await deleteSkill('s9')
    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    expect(urls).toEqual([
      '/api/v1/skills/imports/git',
      '/api/v1/skills/imports/st1/install',
      '/api/v1/skills/imports/st1/reject',
      '/api/v1/skills/imports/st1',
      '/api/v1/skills/s9/enable',
      '/api/v1/skills/s9/disable',
      '/api/v1/skills/s9',
    ])
    expect(fetchMock.mock.calls[3][1].method).toBe('DELETE')
    expect(fetchMock.mock.calls[6][1].method).toBe('DELETE')
  })
})
