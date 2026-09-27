// 文件名:GraphToolbar.spec.ts
// 用途:GraphToolbar 单元测试:验证高频控件常驻、低频控件收进溢出菜单,以及浮动窗口按钮已被移除。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import GraphToolbar from '@/features/workspace/graph/components/GraphToolbar.vue'

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
