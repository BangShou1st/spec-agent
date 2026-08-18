import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GraphQuestionNode from '@/components/graph/GraphQuestionNode.vue'
import type { SpecAgentGraphNodeData, GraphAnswerPresentation } from '@/graph/graphProjection'
import type { GraphWorkspaceNodeView, GraphWorkspaceOptionView } from '@/api/types'

function option(id: string, label: string, impact: string | null): GraphWorkspaceOptionView {
  return { id, label, impact }
}

function nodeData(overrides: Partial<GraphWorkspaceNodeView> = {}): GraphWorkspaceNodeView {
  return {
    id: 'n1',
    projectId: 'p1',
    parentNodeId: null,
    supersedesNodeId: null,
    question: 'What outcome matters most?',
    purpose: 'Clarify the primary goal.',
    options: [
      option('opt-a', 'Product team', 'Fastest value'),
      option('opt-b', 'Engineering team', null),
    ],
    allowFreeAnswer: true,
    createdAt: '2026-08-18T00:00:00Z',
    ...overrides,
  }
}

function answer(routeId: string, overrides: Partial<GraphAnswerPresentation> = {}): GraphAnswerPresentation {
  return {
    routeId,
    selectedOptionId: null,
    selectedOptionLabel: null,
    freeText: 'answer text',
    isPrimary: false,
    ...overrides,
  }
}

function currentData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: nodeData(),
    routeIds: ['r1'],
    answers: [],
    primaryAnswer: null,
    isCurrent: true,
    canAnswer: true,
    isExpanded: false,
    isShared: false,
    visualWeight: 'active',
    ...overrides,
  }
}

function historicalData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: nodeData({ parentNodeId: 'n0' }),
    routeIds: ['r1', 'r2'],
    answers: [
      answer('r1', { selectedOptionId: 'opt-a', selectedOptionLabel: 'Product team', freeText: 'Keep this exact user answer.', isPrimary: true }),
      answer('r2', { freeText: 'Second route answer.' }),
    ],
    primaryAnswer: {
      routeId: 'r1',
      selectedOptionId: 'opt-a',
      selectedOptionLabel: 'Product team',
      freeText: 'Keep this exact user answer.',
      isPrimary: true,
    },
    isCurrent: false,
    canAnswer: false,
    isExpanded: false,
    isShared: true,
    visualWeight: 'normal',
    ...overrides,
  }
}

describe('graph question node', () => {
  it('renders current question, purpose, option labels and impacts verbatim', () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    expect(wrapper.text()).toContain('What outcome matters most?')
    expect(wrapper.text()).toContain('Clarify the primary goal.')
    expect(wrapper.text()).toContain('Product team')
    expect(wrapper.text()).toContain('Fastest value')
    expect(wrapper.text()).toContain('Engineering team')
  })

  it('submits the exact backend option id with an option-only payload', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    await wrapper.find('input[type=radio][value="opt-b"]').setValue()
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('submit-answer')?.[0]).toEqual([{ selectedOptionId: 'opt-b', freeText: null }])
  })

  it('submits a free-text-only payload', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    await wrapper.find('[data-test="free-text"]').setValue('free text answer')
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('submit-answer')?.[0]).toEqual([{ selectedOptionId: null, freeText: 'free text answer' }])
  })

  it('submits combined option + free text payload', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    await wrapper.find('input[type=radio][value="opt-a"]').setValue()
    await wrapper.find('[data-test="free-text"]').setValue('with explanation')
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('submit-answer')?.[0]).toEqual([
      { selectedOptionId: 'opt-a', freeText: 'with explanation' },
    ])
  })

  it('disables submit with no input and while submitting', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    expect((wrapper.find('[data-test="submit-answer"]').attributes('disabled')) !== undefined).toBe(true)
    await wrapper.find('input[type=radio][value="opt-a"]').setValue()
    expect(wrapper.find('[data-test="submit-answer"]').attributes('disabled')).toBeUndefined()
    await wrapper.setProps({ submitting: true })
    expect(wrapper.find('[data-test="submit-answer"]').attributes('disabled')).toBeDefined()
  })

  it('free text is hidden when the node does not allow it', () => {
    const wrapper = mount(GraphQuestionNode, {
      props: { data: currentData({ node: nodeData({ allowFreeAnswer: false }) }), submitting: false, pending: false },
    })
    expect(wrapper.find('[data-test="free-text"]').exists()).toBe(false)
  })

  it('local answer input resets when the node changes', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    await wrapper.find('[data-test="free-text"]').setValue('stale draft')
    await wrapper.setProps({ data: currentData({ node: nodeData({ id: 'n2' }) }) })
    expect((wrapper.find('[data-test="free-text"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('historical node has no answer inputs and shows option label + clamped summary', () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: historicalData(), submitting: false, pending: false } })
    expect(wrapper.find('[data-test="free-text"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="submit-answer"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Product team')
    expect(wrapper.text()).toContain('Keep this exact user answer.')
    expect(wrapper.find('.graph-answer-summary--clamped').exists()).toBe(true)
  })

  it('expanded historical node shows full question, purpose, all options and per-route answers', async () => {
    const expanded = historicalData({ isExpanded: true })
    const wrapper = mount(GraphQuestionNode, { props: { data: expanded, submitting: false, pending: false } })
    expect(wrapper.find('.graph-answer-summary--clamped').exists()).toBe(false)
    expect(wrapper.find('.graph-node-details').exists()).toBe(true)
    expect(wrapper.text()).toContain('Second route answer.')
  })

  it('toggle-expanded emits on demand', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: historicalData(), submitting: false, pending: false } })
    await wrapper.find('[data-test="toggle-expanded"]').trigger('click')
    expect(wrapper.emitted('toggle-expanded')?.[0]).toEqual(['n1'])
  })

  it('current node gets the large class and historical node the compact class', () => {
    const current = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    expect(current.find('.graph-question-node--current').exists()).toBe(true)
    const historical = mount(GraphQuestionNode, { props: { data: historicalData(), submitting: false, pending: false } })
    expect(historical.find('.graph-question-node--historical').exists()).toBe(true)
  })

  it('header is exposed as the drag handle and body controls are not draggable', () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: currentData(), submitting: false, pending: false } })
    expect(wrapper.find('[data-test="node-drag-handle"]').classes()).toContain('graph-question-node__header')
    const body = wrapper.find('.graph-question-node__body')
    expect(body.classes()).toContain('nodrag')
    expect(wrapper.find('[data-test="free-text"]').classes()).toContain('nodrag')
    expect(wrapper.find('button[data-test="submit-answer"]').classes()).toContain('nodrag')
  })

  it('historical fork/regenerate only emit intent upward', async () => {
    const wrapper = mount(GraphQuestionNode, { props: { data: historicalData(), submitting: false, pending: false } })
    await wrapper.find('[data-test="fork-node"]').trigger('click')
    await wrapper.find('[data-test="regenerate-node"]').trigger('click')
    expect(wrapper.emitted('fork')?.[0]).toEqual(['n1'])
    expect(wrapper.emitted('regenerate')?.[0]).toEqual(['n1'])
  })
})
