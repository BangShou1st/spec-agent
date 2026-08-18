import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ClarificationPanel from '@/components/ClarificationPanel.vue'
import { makeNode, makeRoute } from '@/test/fixtures'
import type { NodeOptionResponse } from '@/api/types'

const OPTION_A: NodeOptionResponse = { id: 'opt-a', label: 'Small scope', impact: 'Limits the scope' }
const OPTION_B: NodeOptionResponse = { id: 'opt-b', label: 'Large scope', impact: 'Widens the scope' }

function mountPanel(overrides: Record<string, unknown> = {}) {
  return mount(ClarificationPanel, {
    props: {
      activeRoute: makeRoute({ isActive: true }),
      activeNode: null,
      drafting: false,
      submitting: false,
      feedback: null,
      ...overrides,
    },
  })
}

describe('ClarificationPanel', () => {
  it('shows an honest empty state when there is no active route', () => {
    const wrapper = mountPanel({ activeRoute: null })
    expect(wrapper.text()).toContain('No active route')
    expect(wrapper.find('[data-test="question"]').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('shows the explicit draft button when the active route has no node', () => {
    const wrapper = mountPanel({ activeNode: null })
    const button = wrapper.find('button')
    expect(button.text()).toContain('Draft first question')
    expect(wrapper.find('[data-test="question"]').exists()).toBe(false)
  })

  it('emits a draft event only when clicked', async () => {
    const wrapper = mountPanel({ activeNode: null })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('draft')).toHaveLength(1)
  })

  it('disables and relabels the draft button while drafting', () => {
    const wrapper = mountPanel({ activeNode: null, drafting: true })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    expect(wrapper.find('button').text()).toContain('Drafting question…')
  })

  it('renders the question and purpose', () => {
    const activeNode = makeNode({ question: 'Who is the primary user?', purpose: 'Identifies the audience' })
    const wrapper = mountPanel({ activeNode })
    expect(wrapper.find('[data-test="question"]').text()).toBe('Who is the primary user?')
    expect(wrapper.find('[data-test="purpose"]').text()).toBe('Identifies the audience')
  })

  it('renders options with runtime-owned ids and impact, never editable', () => {
    const activeNode = makeNode({ options: [OPTION_A, OPTION_B] })
    const wrapper = mountPanel({ activeNode })
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios).toHaveLength(2)
    expect(radios[0].attributes('value')).toBe('opt-a')
    expect(radios[1].attributes('value')).toBe('opt-b')
    expect(wrapper.text()).toContain('Small scope')
    expect(wrapper.text()).toContain('Limits the scope')
    const inputs = wrapper.findAll('input')
    expect(inputs.every((input) => input.attributes('value') === 'opt-a' || input.attributes('value') === 'opt-b')).toBe(true)
  })

  it('shows the free-text editor only when the node allows free answers', async () => {
    const withFree = makeNode({ allowFreeAnswer: true })
    const withoutFree = makeNode({ allowFreeAnswer: false })
    const wrapperA = mountPanel({ activeNode: withFree })
    expect(wrapperA.find('[data-test="free-text"]').exists()).toBe(true)
    const wrapperB = mountPanel({ activeNode: withoutFree })
    expect(wrapperB.find('[data-test="free-text"]').exists()).toBe(false)
  })

  it('emits an option-only answer payload', async () => {
    const activeNode = makeNode({ options: [OPTION_A, OPTION_B], allowFreeAnswer: true })
    const wrapper = mountPanel({ activeNode })
    await wrapper.find('input[type="radio"][value="opt-a"]').setValue()
    await wrapper.find('[data-test="submit-answer"]').trigger('click')

    expect(wrapper.emitted('answer')).toHaveLength(1)
    expect(wrapper.emitted('answer')![0][0]).toEqual({ selectedOptionId: 'opt-a' })
  })

  it('emits a free-text-only answer payload', async () => {
    const activeNode = makeNode({ allowFreeAnswer: true })
    const wrapper = mountPanel({ activeNode })
    await wrapper.find('[data-test="free-text"]').setValue('We need a single-user tool')
    await wrapper.find('[data-test="submit-answer"]').trigger('click')

    expect(wrapper.emitted('answer')![0][0]).toEqual({ freeText: 'We need a single-user tool' })
  })

  it('emits a combined option + free-text payload', async () => {
    const activeNode = makeNode({ options: [OPTION_A], allowFreeAnswer: true })
    const wrapper = mountPanel({ activeNode })
    await wrapper.find('input[type="radio"][value="opt-a"]').setValue()
    await wrapper.find('[data-test="free-text"]').setValue(' with explanation')
    await wrapper.find('[data-test="submit-answer"]').trigger('click')

    expect(wrapper.emitted('answer')![0][0]).toEqual({
      selectedOptionId: 'opt-a',
      freeText: 'with explanation',
    })
  })

  it('does not emit an answer when there is no input', async () => {
    const activeNode = makeNode({ allowFreeAnswer: true })
    const wrapper = mountPanel({ activeNode })
    const submit = wrapper.find('[data-test="submit-answer"]')
    expect(submit.attributes('disabled')).toBeDefined()
    await submit.trigger('click')
    expect(wrapper.emitted('answer')).toBeUndefined()
  })

  it('disables and relabels the submit button while submitting', async () => {
    const activeNode = makeNode({ options: [OPTION_A], allowFreeAnswer: true })
    const wrapper = mountPanel({ activeNode, submitting: true })
    const submit = wrapper.find('[data-test="submit-answer"]')
    expect(submit.attributes('disabled')).toBeDefined()
    expect(submit.text()).toContain('Processing answer…')
    await submit.trigger('click')
    expect(wrapper.emitted('answer')).toBeUndefined()
  })

  it('resets local selection when the backend serves a different node', async () => {
    const first = makeNode({ options: [OPTION_A], allowFreeAnswer: true })
    const wrapper = mountPanel({ activeNode: first })
    await wrapper.find('input[type="radio"][value="opt-a"]').setValue()

    await wrapper.setProps({ activeNode: makeNode({ options: [OPTION_B], allowFreeAnswer: true }) })

    await wrapper.find('input[type="radio"][value="opt-b"]').setValue()
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('answer')![0][0]).toEqual({ selectedOptionId: 'opt-b' })
  })

  it('shows brief command feedback from the workspace flow', () => {
    const wrapper = mountPanel({ activeNode: makeNode(), feedback: 'Answer recorded.' })
    expect(wrapper.find('[data-test="feedback"]').text()).toBe('Answer recorded.')
  })
})