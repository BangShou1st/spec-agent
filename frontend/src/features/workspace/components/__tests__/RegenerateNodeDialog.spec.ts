// 文件名:RegenerateNodeDialog.spec.ts
// 用途:RegenerateNodeDialog 组件单元测试,验证重新生成节点时只收集"方向指令"输入、共享节点需先选定查看路线、空指令拦截与取消行为。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RegenerateNodeDialog from '@/features/workspace/components/RegenerateNodeDialog.vue'
import { makeNode } from '@/test/fixtures'

function mountDialog(sourceRouteId: string | null = 'route-1') {
  return mount(RegenerateNodeDialog, {
    props: {
      open: true,
      node: makeNode({ id: 'lnode-2', parentNodeId: 'lnode-1', question: 'What scope is required?' }),
      sourceRouteId,
      pending: false,
    },
  })
}

describe('RegenerateNodeDialog', () => {
  it('asks only for a direction and emits runtime-owned minimal input', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="regenerate-instruction"]').setValue('把问题聚焦到可执行范围')
    await wrapper.find('[data-test="regenerate-submit"]').trigger('click')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({
      sourceRouteId: 'route-1',
      instruction: '把问题聚焦到可执行范围',
    })
    expect(wrapper.find('[data-test="regenerate-question"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="replacement-option-row"]').exists()).toBe(false)
  })

  it('blocks a shared node until the reading route is explicit', () => {
    const wrapper = mountDialog(null)
    expect(wrapper.find('[data-test="regenerate-source-blocker"]').text()).toContain('当前查看')
    expect(wrapper.find('[data-test="regenerate-submit"]').attributes('disabled')).toBeDefined()
  })

  it('requires a nonblank direction', async () => {
    const wrapper = mountDialog()
    expect(wrapper.find('[data-test="regenerate-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="regenerate-instruction"]').setValue('  ')
    expect(wrapper.find('[data-test="regenerate-submit"]').attributes('disabled')).toBeDefined()
  })

  it('closes without submitting on cancel', async () => {
    const wrapper = mountDialog()
    await wrapper.find('[data-test="regenerate-cancel"]').trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
