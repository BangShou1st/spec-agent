import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillsList from '@/components/skills/SkillsList.vue'

const skills = [
  { skillId: 's1', name: 'Research', description: 'desc one', sourceKind: 'BUILTIN', versionId: 'v1', enabled: true, createdAt: '2026-01-01' },
  { skillId: 's2', name: 'Mine', description: 'desc two', sourceKind: 'UPLOAD_ZIP', versionId: null, enabled: false, createdAt: '2026-01-02' },
]

describe('SkillsList', () => {
  it('renders rows with text status', () => {
    const w = mount(SkillsList, { props: { skills, loading: false } })
    expect(w.get('[data-test="skill-row-s1"]'))
    expect(w.get('[data-test="skill-status-s1"]').text()).toContain('已启用')
    expect(w.get('[data-test="skill-status-s2"]').text()).toContain('已禁用')
  })

  it('emits select, enable, and disable', async () => {
    const w = mount(SkillsList, { props: { skills, loading: false } })
    await w.get('[data-test="skill-select-s1"]').trigger('click')
    expect(w.emitted('select')).toEqual([['s1']])
    await w.get('[data-test="skill-more-s2"] summary').trigger('click')
    await w.get('[data-test="skill-enable-s2"]').trigger('click')
    expect(w.emitted('enable')).toEqual([['s2']])
  })

  it('asks for confirmation before delete', async () => {
    const w = mount(SkillsList, { props: { skills, loading: false } })
    await w.get('[data-test="skill-more-s1"] summary').trigger('click')
    await w.get('[data-test="skill-delete-s1"]').trigger('click')
    await w.get('[data-test="skill-delete-confirm-s1"]').trigger('click')
    expect(w.emitted('remove')).toEqual([['s1']])
  })
})
