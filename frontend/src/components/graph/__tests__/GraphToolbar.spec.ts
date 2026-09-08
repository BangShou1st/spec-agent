import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import GraphToolbar from '@/components/graph/GraphToolbar.vue'

describe('GraphToolbar', () => {
  it('keeps frequent controls visible and low-frequency controls in overflow', () => {
    const wrapper = mount(GraphToolbar, {
      global: { plugins: [createPinia()] },
    })

    expect(wrapper.find('[data-test="add-idea"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="zoom-in"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="zoom-out"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="fit-view"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="toolbar-more"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="toolbar-more"]').attributes('open')).toBeUndefined()

    expect(wrapper.find('[data-test="open-routes"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="open-inspector"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="reset-windows"]').exists()).toBe(false)
  })
})
