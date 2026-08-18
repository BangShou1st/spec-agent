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
    expect(wrapper.text()).toContain('归档该路线？')
    expect(wrapper.find('[data-test="confirm-route-action"]').text()).toContain('归档路线')
    await wrapper.find('[data-test="confirm-route-action"]').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('delete copy states this is a soft-delete preserving historical records', () => {
    const wrapper = mountDialog('delete')
    const description = wrapper.find('[data-test="confirm-description"]').text()
    expect(wrapper.text()).toContain('删除该路线？')
    expect(description).toContain('软删除')
    expect(description).toContain('历史运行记录会被保留')
    expect(description).toContain('不会被物理删除')
    expect(wrapper.find('[data-test="confirm-route-action"]').text()).toContain('删除路线')
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
