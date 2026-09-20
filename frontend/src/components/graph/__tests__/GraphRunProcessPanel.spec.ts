import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GraphRunProcessPanel from '@/components/graph/GraphRunProcessPanel.vue'
import type { RunProgressStep } from '@/api/agentRuns'

function step(overrides: Partial<RunProgressStep> & { sequence: number }): RunProgressStep {
  return {
    phase: 'STATE_UPDATING',
    event: 'PROCESS_NOTE',
    summary: null,
    items: null,
    at: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

describe('GraphRunProcessPanel', () => {
  it('renders the phase copy with a spinner while running', () => {
    const wrapper = mount(GraphRunProcessPanel, {
      props: { phase: 'STATE_UPDATING', running: true },
    })
    expect(wrapper.find('[data-test="run-process-panel"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在整理需求')
    expect(wrapper.find('.run-process-panel__spinner').exists()).toBe(true)
  })

  it('renders only steps that carry a composed summary, newest last', () => {
    const wrapper = mount(GraphRunProcessPanel, {
      props: {
        phase: 'DECIDING',
        running: true,
        steps: [
          step({ sequence: 1, summary: null }),
          step({ sequence: 2, summary: '需求要点整理完成，共 2 条', items: ['要点一', '要点二'] }),
        ],
      },
    })
    const stepTexts = wrapper.findAll('.run-process-panel__step')
    expect(stepTexts).toHaveLength(1)
    expect(stepTexts[0].classes()).toContain('run-process-panel__step--latest')
    expect(wrapper.text()).toContain('需求要点整理完成，共 2 条')
    expect(wrapper.findAll('.run-process-panel__items li')).toHaveLength(2)
  })

  it('compact mode keeps only the latest tail of many steps', () => {
    const steps = [1, 2, 3, 4, 5].map((i) => step({ sequence: i, summary: `步骤${i}` }))
    const wrapper = mount(GraphRunProcessPanel, {
      props: { phase: 'DECIDING', running: true, steps, compact: true },
    })
    const stepTexts = wrapper.findAll('.run-process-panel__step')
    expect(stepTexts).toHaveLength(3)
    expect(stepTexts[0].text()).toContain('步骤3')
    expect(stepTexts[2].text()).toContain('步骤5')
  })

  it('a failed run drops the spinner but keeps the timeline', () => {
    const wrapper = mount(GraphRunProcessPanel, {
      props: {
        phase: 'FAILED',
        running: false,
        summary: '需求要点整理完成，共 1 条',
        steps: [step({ sequence: 1, summary: '需求要点整理完成，共 1 条' })],
      },
    })
    expect(wrapper.find('.run-process-panel__spinner').exists()).toBe(false)
    expect(wrapper.find('.run-process-panel--failed').exists()).toBe(true)
    expect(wrapper.text()).toContain('需求要点整理完成，共 1 条')
  })
})
