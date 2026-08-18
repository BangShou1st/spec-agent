import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ResizableSidebar from '@/components/workspace/ResizableSidebar.vue'

describe('resizable sidebar', () => {
  it('renders open with the slot content and the given width', () => {
    const wrapper = mount(ResizableSidebar, {
      props: { side: 'left', open: true, width: 280, minWidth: 220, maxWidth: 420 },
      slots: { default: '<div data-test="slot-content">routes</div>' },
    })
    expect(wrapper.find('[data-test="left-sidebar"]').attributes('style')).toContain('280px')
    expect(wrapper.find('[data-test="slot-content"]').exists()).toBe(true)
  })

  it('collapses independently of the other sidebar', async () => {
    const wrapper = mount(ResizableSidebar, {
      props: { side: 'right', open: true, width: 380, minWidth: 300, maxWidth: 600 },
    })
    await wrapper.find('[data-test="toggle-right"]').trigger('click')
    expect(wrapper.emitted('update:open')?.[0]).toEqual([false])
    await wrapper.setProps({ open: false })
    expect(wrapper.find('[data-test="sidebar-content"]').exists()).toBe(false)
  })

  it('clamps resize deltas to the allowed range', async () => {
    const wrapper = mount(ResizableSidebar, {
      props: { side: 'left', open: true, width: 280, minWidth: 220, maxWidth: 420 },
    })
    const handle = wrapper.find('[data-test="resize-handle-left"]')
    await handle.trigger('pointerdown', { clientX: 100 })
    // Drag far beyond the max.
    await window.dispatchEvent(new MouseEvent('pointermove', { clientX: 2000 }))
    await window.dispatchEvent(new MouseEvent('pointerup'))
    const emitted = wrapper.emitted('update:width')
    expect(emitted).toBeDefined()
    const last = emitted?.[emitted.length - 1]?.[0] as number
    expect(last).toBeGreaterThanOrEqual(220)
    expect(last).toBeLessThanOrEqual(420)
  })
})
