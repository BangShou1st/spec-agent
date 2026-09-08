import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RecoveryNotice from '../RecoveryNotice.vue'
import type { RecoveryNoticeModel } from '@/presentation/recoveryPresentation'

function model(overrides: Partial<RecoveryNoticeModel> = {}): RecoveryNoticeModel {
  return {
    kind: 'saved',
    title: '回答已经保存',
    message: '后续生成没有完成，不需要重新填写回答。',
    action: 'resume-answer',
    actionLabel: '继续生成',
    ...overrides,
  }
}

describe('RecoveryNotice', () => {
  it('renders one card with title, message and a single primary CTA', () => {
    const wrapper = mount(RecoveryNotice, { props: { model: model() } })
    expect(wrapper.find('[data-test="recovery-notice"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="recovery-title"]').text()).toContain('回答已经保存')
    expect(wrapper.find('[data-test="recovery-message"]').text()).toContain('不需要重新填写回答')
    const actions = wrapper.findAll('[data-test="recovery-action"]')
    expect(actions).toHaveLength(1)
    expect(actions[0].text()).toContain('继续生成')
  })

  it('emits the semantic action when the CTA is clicked', async () => {
    const wrapper = mount(RecoveryNotice, { props: { model: model() } })
    await wrapper.find('[data-test="recovery-action"]').trigger('click')
    expect(wrapper.emitted('action')).toEqual([['resume-answer']])
  })

  it('renders no CTA for blocked notices and exposes no raw error code', () => {
    const wrapper = mount(RecoveryNotice, {
      props: {
        model: model({
          kind: 'blocked',
          title: '当前操作无法继续',
          message: '请按提示处理后再试，不需要重复提交。',
          action: null,
          actionLabel: null,
        }),
      },
    })
    expect(wrapper.findAll('[data-test="recovery-action"]')).toHaveLength(0)
    expect(wrapper.text()).not.toMatch(/POLICY_DENIED|AGENT_RUN_|UNKNOWN_ERROR/)
  })
})
