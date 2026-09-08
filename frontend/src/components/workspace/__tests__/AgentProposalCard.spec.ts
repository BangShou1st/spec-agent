import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AgentProposalCard from '../AgentProposalCard.vue'

describe('AgentProposalCard', () => {
  it('renders a human-readable action label, never the raw family', () => {
    const wrapper = mount(AgentProposalCard, {
      props: {
        actionFamily: 'CREATE_NODE',
        message: '建议创建节点',
        nodeContext: 'Q3 · 数据与安全',
        accepting: false,
        rejecting: false,
      },
    })
    expect(wrapper.find('[data-test="proposal-card"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('创建节点')
    expect(wrapper.text()).not.toContain('CREATE_NODE')
    expect(wrapper.text()).toContain('建议创建节点')
    expect(wrapper.text()).toContain('Q3 · 数据与安全')
  })

  it('renders no fabricated impact text', () => {
    const wrapper = mount(AgentProposalCard, {
      props: {
        actionFamily: 'CREATE_NODE',
        message: null,
        nodeContext: 'Q3',
        accepting: false,
        rejecting: false,
      },
    })
    expect(wrapper.text()).not.toMatch(/影响|风险|预计/)
  })

  it('exposes confirm/reject actions and emits intents', async () => {
    const wrapper = mount(AgentProposalCard, {
      props: {
        actionFamily: 'CONNECT_NODE',
        message: null,
        nodeContext: 'Q3',
        accepting: false,
        rejecting: false,
      },
    })
    expect(wrapper.find('[data-test="proposal-accept"]').text()).toContain('确认执行')
    expect(wrapper.find('[data-test="proposal-reject"]').text()).toContain('拒绝')
    await wrapper.find('[data-test="proposal-accept"]').trigger('click')
    await wrapper.find('[data-test="proposal-reject"]').trigger('click')
    expect(wrapper.emitted('accept')).toHaveLength(1)
    expect(wrapper.emitted('reject')).toHaveLength(1)
  })

  it('exposes exactly one accept/reject control pair', () => {
    const wrapper = mount(AgentProposalCard, {
      props: {
        actionFamily: 'CREATE_NODE',
        message: '建议创建节点',
        nodeContext: 'Q3',
        accepting: false,
        rejecting: false,
      },
    })
    expect(wrapper.findAll('[data-test="proposal-accept"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="proposal-reject"]')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('CREATE_NODE')
  })

  it('respects pending flags with disabled state', () => {
    const wrapper = mount(AgentProposalCard, {
      props: {
        actionFamily: 'CREATE_NODE',
        message: null,
        nodeContext: 'Q3',
        accepting: true,
        rejecting: false,
      },
    })
    expect(wrapper.find('[data-test="proposal-accept"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="proposal-reject"]').attributes('disabled')).toBeDefined()
  })
})
