// 文件名:SkillsList.spec.ts
// 用途:Skills 列表组件测试:验证行内名称/描述/来源渲染与启用开关、删除事件。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillsList from '@/features/skills/components/SkillsList.vue'

const skills = [
  { skillId: 's1', name: 'Research', description: 'desc one', sourceKind: 'BUILTIN', versionId: 'v1', enabled: true, createdAt: '2026-01-01' },
  { skillId: 's2', name: 'Mine', description: 'desc two', sourceKind: 'UPLOAD_ZIP', versionId: null, enabled: false, createdAt: '2026-01-02' },
]

describe('SkillsList', () => {
  it('renders rows with a switch and a text status', () => {
    const w = mount(SkillsList, { props: { skills, loading: false } })
    expect(w.get('[data-test="skill-row-s1"]'))
    expect(w.get('[data-test="skill-status-s1"]').text()).toContain('已启用')
    expect(w.get('[data-test="skill-status-s2"]').text()).toContain('已禁用')

    // 生命周期控件是真实的 switch,所以它的状态会暴露给辅助技术(AT)。
    const on = w.get('[data-test="skill-disable-s1"]')
    expect(on.attributes('role')).toBe('switch')
    expect(on.attributes('aria-checked')).toBe('true')
    expect(w.get('[data-test="skill-enable-s2"]').attributes('aria-checked')).toBe('false')
    // `find` 是存在性检查;`get` 断言必须存在,不需要再写 exists()。
    expect(w.find('[data-test="skill-delete-s1"]').exists()).toBe(true)
  })

  it('emits select, enable, and disable', async () => {
    const w = mount(SkillsList, { props: { skills, loading: false } })
    await w.get('[data-test="skill-select-s1"]').trigger('click')
    expect(w.emitted('select')).toEqual([['s1']])
    await w.get('[data-test="skill-enable-s2"]').trigger('click')
    expect(w.emitted('enable')).toEqual([['s2']])
    await w.get('[data-test="skill-disable-s1"]').trigger('click')
    expect(w.emitted('disable')).toEqual([['s1']])
  })

  it('asks for confirmation before delete', async () => {
    const w = mount(SkillsList, { props: { skills, loading: false } })
    await w.get('[data-test="skill-delete-s1"]').trigger('click')
    expect(w.emitted('remove')).toBeUndefined()
    await w.get('[data-test="skill-delete-confirm-s1"]').trigger('click')
    expect(w.emitted('remove')).toEqual([['s1']])
  })
})
