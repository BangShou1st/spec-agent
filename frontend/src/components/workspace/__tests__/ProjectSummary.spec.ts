import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ProjectSummary from '../ProjectSummary.vue'
import { makeRequirementState } from '@/test/fixtures'

describe('ProjectSummary', () => {
  it('shows readable counts and route context without raw ids', () => {
    const wrapper = mount(ProjectSummary, {
      props: {
        requirementState: makeRequirementState({
          confirmed: [
            { kind: 'goal', text: 'c1', status: 'confirmed', confidence: 0.9, sourceNodeId: 'n1', sourceAnswerId: 'a1' },
            { kind: 'goal', text: 'c2', status: 'confirmed', confidence: 0.8, sourceNodeId: 'n2', sourceAnswerId: 'a2' },
          ],
          unresolved: [],
          assumed: [],
          rejected: [],
        }),
        routeLabel: '主路线',
        routeId: 'route-uuid-1234567890',
        loading: false,
      },
    })
    expect(wrapper.find('[data-test="project-summary"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('已确认')
    expect(wrapper.text()).toContain('主路线')
    expect(wrapper.text()).not.toContain('route-uuid-1234567890')
    expect(wrapper.find('[data-test="open-requirements"]').exists()).toBe(true)
  })

  it('emits open-requirements on entry click', async () => {
    const wrapper = mount(ProjectSummary, {
      props: { requirementState: null, routeLabel: '主路线', routeId: null, loading: false },
    })
    await wrapper.find('[data-test="open-requirements"]').trigger('click')
    expect(wrapper.emitted('open-requirements')).toHaveLength(1)
  })
})
