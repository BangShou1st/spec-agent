import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RelationProposalDialog from '@/components/graph/RelationProposalDialog.vue'

function byTestId(testId: string): string {
  return `[data-test="${testId}"]`
}

function mountDialog(overrides: Record<string, unknown> = {}) {
  return mount(RelationProposalDialog, {
    props: {
      open: true,
      sourceNodeId: 'a',
      targetNodeId: 'b',
      sourceLabel: 'Node A',
      targetLabel: 'Node B',
      pending: false,
      ...overrides,
    },
    global: { stubs: { teleport: true } },
  })
}

describe('RelationProposalDialog', () => {
  it('Confirm emits the selected type with the canonical direction', async () => {
    const wrapper = mountDialog()
    await wrapper.find(byTestId('relation-type-supports')).trigger('click')
    await wrapper.find(byTestId('relation-confirm')).trigger('click')
    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual({
      sourceNodeId: 'a',
      targetNodeId: 'b',
      relationType: 'SUPPORTS',
    })
  })

  it('Reverse swaps the direction for directional types', async () => {
    const wrapper = mountDialog()
    await wrapper.find(byTestId('relation-type-depends_on')).trigger('click')
    await wrapper.find(byTestId('relation-reverse')).trigger('click')
    await wrapper.find(byTestId('relation-confirm')).trigger('click')
    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual({
      sourceNodeId: 'b',
      targetNodeId: 'a',
      relationType: 'DEPENDS_ON',
    })
  })

  it('Confirm disabled while pending', () => {
    const pending = mountDialog({ pending: true })
    const confirm = pending.find(byTestId('relation-confirm'))
    expect((confirm.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('Cancel emits cancel', async () => {
    const wrapper = mountDialog()
    await wrapper.find(byTestId('relation-cancel')).trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('Escape on the backdrop emits cancel', async () => {
    const wrapper = mountDialog()
    await wrapper.find(byTestId('relation-proposal')).trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })
})