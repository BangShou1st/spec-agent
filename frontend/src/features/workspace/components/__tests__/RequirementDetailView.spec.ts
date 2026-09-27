// 文件名:RequirementDetailView.spec.ts
// 用途:RequirementDetailView 组件单元测试,验证需求断言文案默认隐藏技术元数据,以及技术详情折叠展示与返回事件。
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RequirementDetailView from '@/features/workspace/components/RequirementDetailView.vue'
import { makeClaim, makeRequirementState } from '@/test/fixtures'

describe('RequirementDetailView', () => {
  it('shows readable claim text while hiding technical metadata by default', () => {
    const wrapper = mount(RequirementDetailView, {
      props: {
        requirementState: makeRequirementState({
          confirmed: [
            makeClaim({
              kind: 'goal',
              text: '用户可以登录',
              confidence: 0.9,
              sourceNodeId: 'node-uuid-1',
              sourceAnswerId: 'answer-uuid-1',
            }),
          ],
        }),
        routeLabel: '主路线',
        routeId: 'route-uuid-1234567890',
        loading: false,
      },
    })
    expect(wrapper.find('[data-test="requirement-detail"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('用户可以登录')
    expect(wrapper.text()).not.toContain('node-uuid-1')
    expect(wrapper.text()).not.toContain('answer-uuid-1')
    expect(wrapper.text()).not.toContain('route-uuid-1234567890')
    expect(wrapper.find('[data-test="claim-tech"]').exists()).toBe(false)
  })

  it('reveals technical details only inside the disclosure and emits back', async () => {
    const wrapper = mount(RequirementDetailView, {
      props: {
        requirementState: makeRequirementState({
          confirmed: [makeClaim({ text: '用户可以登录' })],
        }),
        routeLabel: '主路线',
        routeId: 'r1',
        loading: false,
      },
    })
    await wrapper.find('[data-test="claim-tech-toggle"]').trigger('click')
    expect(wrapper.find('[data-test="claim-tech"]').exists()).toBe(true)
    await wrapper.find('[data-test="requirement-back"]').trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
  })
})
