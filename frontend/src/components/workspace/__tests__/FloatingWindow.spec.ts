import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import FloatingWindow from '@/components/workspace/FloatingWindow.vue'
import type { FloatingWindowPreference } from '@/graph/graphTypes'

const initial: FloatingWindowPreference = {
  x: 40,
  y: 60,
  width: 320,
  height: 360,
  open: true,
  positionMode: 'auto',
}

function mountWindow(state: FloatingWindowPreference = initial) {
  return mount(FloatingWindow, {
    props: {
      name: 'routes',
      title: 'Routes',
      state,
      zIndex: 10,
      minWidth: 240,
      maxWidth: 600,
      minHeight: 200,
      maxHeight: 700,
    },
    slots: { default: '<button data-test="body-button">Body</button>' },
  })
}

function pointer(type: string, clientX: number, clientY: number): MouseEvent {
  return new MouseEvent(type, { bubbles: true, clientX, clientY })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('FloatingWindow', () => {
  it('drags from the title bar and leaves body interaction independent', async () => {
    const wrapper = mountWindow()
    await wrapper.find('[data-test="floating-window-titlebar"]').trigger('pointerdown', {
      clientX: 100,
      clientY: 100,
    })
    window.dispatchEvent(pointer('pointermove', 160, 140))
    const update = wrapper.emitted('update:state')?.at(-1)?.[0] as Partial<FloatingWindowPreference>
    expect(update.x).toBe(100)
    expect(update.y).toBe(100)
    expect(update.positionMode).toBe('manual')
    expect(wrapper.find('[data-test="body-button"]').exists()).toBe(true)
  })

  it('resizes from an edge and clamps to the configured minimum', async () => {
    const wrapper = mountWindow()
    await wrapper.find('[data-test="resize-e"]').trigger('pointerdown', {
      clientX: 100,
      clientY: 100,
    })
    window.dispatchEvent(pointer('pointermove', -500, 100))
    const update = wrapper.emitted('update:state')?.at(-1)?.[0] as Partial<FloatingWindowPreference>
    expect(update.width).toBe(240)
    expect(update.positionMode).toBe('manual')
  })

  it('snaps near an edge, brings itself to front, and emits close/reset intents', async () => {
    const wrapper = mountWindow({ ...initial, x: 40, y: 60 })
    await wrapper.find('[data-test="floating-window-titlebar"]').trigger('pointerdown', {
      clientX: 100,
      clientY: 100,
    })
    window.dispatchEvent(pointer('pointermove', 65, 140))
    const update = wrapper.emitted('update:state')?.at(-1)?.[0] as Partial<FloatingWindowPreference>
    expect(update.x).toBe(0)
    expect(wrapper.emitted('focus')).toBeTruthy()
    await wrapper.find('[data-test="floating-window-reset"]').trigger('click')
    await wrapper.find('[data-test="floating-window-close"]').trigger('click')
    expect(wrapper.emitted('reset')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('recovers a moved window when the viewport changes', async () => {
    const wrapper = mountWindow({ ...initial, x: 2000, y: 2000 })
    window.dispatchEvent(new Event('resize'))
    const update = wrapper.emitted('update:state')?.at(-1)?.[0] as FloatingWindowPreference
    expect(update.x).toBeLessThan(window.innerWidth)
    expect(update.y).toBeLessThan(window.innerHeight)
  })
})
