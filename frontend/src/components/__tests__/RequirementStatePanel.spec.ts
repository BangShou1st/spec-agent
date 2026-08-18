import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RequirementStatePanel from '@/components/RequirementStatePanel.vue'
import { makeRequirementState } from '@/test/fixtures'

describe('RequirementStatePanel', () => {
  it('groups claims into backend-status sections', () => {
    const state = makeRequirementState({
      routeId: 'route-1',
      confirmed: [
        {
          kind: 'goal',
          text: 'Confirmed goal claim',
          status: 'confirmed',
          confidence: 0.9,
          sourceNodeId: 'node-1',
          sourceAnswerId: 'answer-1',
        },
      ],
      assumed: [
        {
          kind: 'assumption',
          text: 'Assumed detail claim',
          status: 'assumed',
          confidence: 0.5,
          sourceNodeId: null,
          sourceAnswerId: null,
        },
      ],
      unresolved: [
        {
          kind: 'open_question',
          text: 'Open boundary question',
          status: 'unresolved',
          confidence: 0.5,
          sourceNodeId: null,
          sourceAnswerId: null,
        },
      ],
      rejected: [
        {
          kind: 'other',
          text: 'Rejected idea',
          status: 'rejected',
          confidence: null,
          sourceNodeId: null,
          sourceAnswerId: null,
        },
      ],
    })
    const wrapper = mount(RequirementStatePanel, { props: { requirementState: state } })

    expect(wrapper.text()).toContain('Confirmed')
    expect(wrapper.text()).toContain('Assumptions')
    expect(wrapper.text()).toContain('Unresolved')
    expect(wrapper.text()).toContain('Rejected')

    const confirmedGroup = wrapper.find('[data-test="claim-group-confirmed"]')
    expect(confirmedGroup.text()).toContain('Confirmed goal claim')
    expect(confirmedGroup.text()).not.toContain('Assumed detail claim')

    const assumedGroup = wrapper.find('[data-test="claim-group-assumed"]')
    expect(assumedGroup.text()).toContain('Assumed detail claim')
    expect(assumedGroup.text()).not.toContain('Confirmed goal claim')

    const unresolvedGroup = wrapper.find('[data-test="claim-group-unresolved"]')
    expect(unresolvedGroup.text()).toContain('Open boundary question')

    const rejectedGroup = wrapper.find('[data-test="claim-group-rejected"]')
    expect(rejectedGroup.text()).toContain('Rejected idea')
    expect(rejectedGroup.find('.claim-card.rejected').exists()).toBe(true)
  })

  it('shows route id and claim source ids as metadata', () => {
    const wrapper = mount(RequirementStatePanel, {
      props: { requirementState: makeRequirementState({ routeId: 'route-42' }) },
    })
    expect(wrapper.text()).toContain('route-42')
    expect(wrapper.text()).toContain('node-1')
    expect(wrapper.text()).toContain('answer-1')
  })

  it('renders a friendly placeholder for empty groups', () => {
    const wrapper = mount(RequirementStatePanel, {
      props: { requirementState: makeRequirementState({ confirmed: [], assumed: [], unresolved: [], rejected: [] }) },
    })
    expect(wrapper.findAll('.muted').some((el) => el.text() === 'None.')).toBe(true)
  })

  it('shows a not-loaded placeholder before the endpoint returns', () => {
    const wrapper = mount(RequirementStatePanel, { props: { requirementState: null } })
    expect(wrapper.text()).toContain('Requirement state is not loaded yet.')
  })
})