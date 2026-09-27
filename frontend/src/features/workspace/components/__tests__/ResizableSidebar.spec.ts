// 文件名:ResizableSidebar.spec.ts
// 用途:ResizableSidebar 组件单元测试,验证侧栏宽度、独立折叠、定位上下文(position)与拖拽调宽的边界钳制。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ResizableSidebar from '@/features/workspace/components/ResizableSidebar.vue'

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

  it('establishes its own containing block for the absolute toggle and resize handle', async () => {
    for (const side of ['left', 'right'] as const) {
      const wrapper = mount(ResizableSidebar, {
        props: { side, open: true, width: 280, minWidth: 220, maxWidth: 420 },
        slots: { default: '<div data-test="slot-content">routes</div>' },
      })
      const aside = wrapper.find(`[data-test="${side}-sidebar"]`)
      // aside 必须是其内部绝对定位的折叠按钮与拖拽手柄的包含块,否则一旦
      // 侧栏从绝对定位的浮层变成普通 flex 子元素,这些按钮就会锚定到
      // 工作台外壳(或视口)上。
      // (真实像素几何在 e2e/workspace-layout.spec.ts 中断言;
      // jsdom 没有布局引擎,getBoundingClientRect 恒为 0。)
      expect(['relative', 'absolute', 'fixed', 'sticky']).toContain(
        window.getComputedStyle(aside.element).position,
      )
    }
  })

  it('clamps resize deltas to the allowed range', async () => {    const wrapper = mount(ResizableSidebar, {
      props: { side: 'left', open: true, width: 280, minWidth: 220, maxWidth: 420 },
    })
    const handle = wrapper.find('[data-test="resize-handle-left"]')
    await handle.trigger('pointerdown', { clientX: 100 })
    // 拖拽远超最大宽度。
    await window.dispatchEvent(new MouseEvent('pointermove', { clientX: 2000 }))
    await window.dispatchEvent(new MouseEvent('pointerup'))
    const emitted = wrapper.emitted('update:width')
    expect(emitted).toBeDefined()
    const last = emitted?.[emitted.length - 1]?.[0] as number
    expect(last).toBeGreaterThanOrEqual(220)
    expect(last).toBeLessThanOrEqual(420)
  })
})
