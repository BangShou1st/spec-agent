import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RegenerateNodeDialog from '@/components/RegenerateNodeDialog.vue'
import { makeRouteLineageNode } from '@/test/fixtures'

const nodeWithOptions = () =>
  makeRouteLineageNode({
    id: 'lnode-2',
    parentNodeId: 'lnode-1',
    question: 'What scope is required?',
    purpose: 'Clarifies scope.',
    options: [
      { id: 'opt-a', label: 'Small scope', impact: 'Reduces scope' },
      { id: 'opt-b', label: 'Large scope', impact: null },
    ],
  })

function mountDialog(node: ReturnType<typeof makeRouteLineageNode> | null = nodeWithOptions(), pending = false) {
  return mount(RegenerateNodeDialog, { props: { open: true, node, pending } })
}

describe('RegenerateNodeDialog', () => {
  it('prefills question, purpose, option labels and impacts from the node', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()

    expect((wrapper.find('[data-test="regenerate-question"]').element as HTMLTextAreaElement).value).toBe(
      'What scope is required?',
    )
    expect((wrapper.find('[data-test="regenerate-purpose"]').element as HTMLTextAreaElement).value).toBe(
      'Clarifies scope.',
    )
    const labels = wrapper.findAll('[data-test="replacement-option-label"]').map((i) => (i.element as HTMLInputElement).value)
    const impacts = wrapper.findAll('[data-test="replacement-option-impact"]').map((i) => (i.element as HTMLInputElement).value)
    expect(labels).toEqual(['Small scope', 'Large scope'])
    expect(impacts).toEqual(['Reduces scope', ''])
  })

  it('copies content only — never option ids — into the emitted request', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="regenerate-submit"]').trigger('click')

    const payload = wrapper.emitted('submit')?.[0]?.[0] as {
      replacementOptions?: Array<Record<string, unknown>>
    }
    expect(payload).toBeDefined()
    expect(payload.replacementOptions?.[0]).not.toHaveProperty('id')
    expect(payload.replacementOptions?.[1]).not.toHaveProperty('id')
    expect(Object.keys(payload).sort()).toEqual([
      'instruction',
      'replacementOptions',
      'replacementPurpose',
      'replacementQuestion',
    ])
  })

  it('allows editing instruction, question, purpose, labels, and impacts', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="regenerate-instruction"]').setValue('Make it narrower')
    await wrapper.find('[data-test="regenerate-question"]').setValue('What scope exactly?')
    await wrapper.find('[data-test="replacement-option-label"]').setValue('Tiny scope')

    await wrapper.find('[data-test="regenerate-submit"]').trigger('click')
    const payload = wrapper.emitted('submit')?.[0]?.[0] as {
      instruction: string
      replacementQuestion: string
      replacementOptions: Array<{ label: string }>
    }
    expect(payload.instruction).toBe('Make it narrower')
    expect(payload.replacementQuestion).toBe('What scope exactly?')
    expect(payload.replacementOptions[0].label).toBe('Tiny scope')
  })

  it('does not expose replacementNodeId/contextSnapshotId/supersedes ids', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    const text = wrapper.text()
    expect(text).not.toContain('replacementNodeId')
    expect(text).not.toContain('contextSnapshotId')
    expect(text).not.toContain('supersedesNodeId')
  })

  it('blocks submit while the replacement question is blank', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="regenerate-question"]').setValue('   ')
    expect(wrapper.find('[data-test="regenerate-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="regenerate-question-error"]').exists()).toBe(true)
  })

  it('blocks submit while any replacement option label is blank', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="replacement-option-label"]').setValue('')
    expect(wrapper.find('[data-test="regenerate-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="replacement-option-error"]').exists()).toBe(true)
  })

  it('adds and removes replacement option rows', async () => {
    const wrapper = mountDialog()
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="replacement-option-add"]').trigger('click')
    expect(wrapper.findAll('[data-test="replacement-option-row"]')).toHaveLength(3)

    await wrapper.find('[data-test="replacement-option-remove"]').trigger('click')
    expect(wrapper.findAll('[data-test="replacement-option-row"]')).toHaveLength(2)
  })

  it('closes without submitting on cancel', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="regenerate-cancel"]').trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
