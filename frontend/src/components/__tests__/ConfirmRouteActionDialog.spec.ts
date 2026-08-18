import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmRouteActionDialog from '@/components/ConfirmRouteActionDialog.vue'

function mountDialog(kind: 'archive' | 'delete', pending = false) {
  return mount(ConfirmRouteActionDialog, {
    props: { open: true, kind, routeLabel: 'Main route', pending },
  })
}

describe('ConfirmRouteActionDialog', () => {
  it('archive requires an explicit confirmation', async () => {
    const wrapper = mountDialog('archive')
    expect(wrapper.text()).toContain('Archive route?')
    expect(wrapper.find('[data-test="confirm-route-action"]').text()).toContain('Archive route')
    await wrapper.find('[data-test="confirm-route-action"]').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('delete copy states this is a soft-delete preserving historical records', () => {
    const wrapper = mountDialog('delete')
    const description = wrapper.find('[data-test="confirm-description"]').text()
    expect(wrapper.text()).toContain('Delete route?')
    expect(description.toLowerCase()).toContain('soft-delete')
    expect(description.toLowerCase()).toContain('preserved')
    // The copy explicitly states shared nodes/answers are NOT physically deleted.
    expect(description.toLowerCase()).toContain('not physically deleted')
    expect(wrapper.find('[data-test="confirm-route-action"]').text()).toContain('Delete route')
  })

  it('cancel does not confirm', async () => {
    const wrapper = mountDialog('delete')
    await wrapper.find('[data-test="cancel-route-action"]').trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('disables buttons while the command is pending', () => {
    const wrapper = mountDialog('archive', true)
    expect(wrapper.find('[data-test="confirm-route-action"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="cancel-route-action"]').attributes('disabled')).toBeDefined()
  })
})
