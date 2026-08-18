import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ForkRouteDialog from '@/components/ForkRouteDialog.vue'
import { makeRouteLineageNode } from '@/test/fixtures'

function mountDialog(open = true, pending = false) {
  return mount(ForkRouteDialog, {
    props: { open, node: makeRouteLineageNode({ id: 'lnode-2' }), pending },
  })
}

describe('ForkRouteDialog', () => {
  it('submits only the user label when provided', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-label"]').setValue('Alternative route')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')

    expect(wrapper.emitted('submit')).toEqual([['Alternative route']])
  })

  it('submits null label when left empty', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-submit"]').trigger('click')

    expect(wrapper.emitted('submit')).toEqual([[null]])
  })

  it('never sends runtime-owned ids with the fork request', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-label"]').setValue('Branch A')
    await wrapper.find('[data-test="fork-submit"]').trigger('click')

    const label = wrapper.emitted('submit')?.[0]?.[0]
    expect(label).toBe('Branch A')
  })

  it('closes without submitting on cancel', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="fork-cancel"]').trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('disables buttons while the fork command is pending', () => {
    const wrapper = mountDialog(true, true)
    expect(wrapper.find('[data-test="fork-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="fork-cancel"]').attributes('disabled')).toBeDefined()
  })
})
