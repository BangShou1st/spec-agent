// 文件名:ConnectionCapabilityBrowser.spec.ts
// 用途:连接能力浏览器组件测试:验证 Tools/Resources/Prompts 三标签页各自渲染对应内容、
//       工具的 schema 按需展开。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConnectionCapabilityBrowser from '@/features/connections/components/ConnectionCapabilityBrowser.vue'

const tools = [{ name: 'search', description: 'd', inputSchema: { type: 'object' }, annotations: {} }]
const resources = [{ uri: 'docs://g', name: 'guide', description: 'd', mimeType: 'text/plain' }]
const prompts = [{ name: 'review', description: 'd', argumentCount: 1 }]

describe('ConnectionCapabilityBrowser', () => {
  it('keeps tools, resources, and prompts on separate tabs', async () => {
    const w = mount(ConnectionCapabilityBrowser, { props: { tools, resources, prompts } })
    expect(w.get('[data-test="cap-tool-search"]'))
    expect(w.find('[data-test="cap-resource-docs://g"]').exists()).toBe(false)
    await w.get('[data-test="cap-tab-resources"]').trigger('click')
    expect(w.get('[data-test="cap-resource-docs://g"]'))
    await w.get('[data-test="cap-tab-prompts"]').trigger('click')
    expect(w.get('[data-test="cap-prompt-review"]'))
  })

  it('expands tool schema on demand', async () => {
    const w = mount(ConnectionCapabilityBrowser, { props: { tools, resources, prompts } })
    await w.get('[data-test="cap-tool-toggle-search"]').trigger('click')
    expect(w.text()).toContain('Input schema')
  })
})
