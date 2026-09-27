// 文件名:NodeRecoveryBar.spec.ts
// 用途:节点失败恢复栏组件单测:动作文案与图标 aria、在途禁用、配置错误
// 路由到"前往模型设置"、过期目标的定位事件、多失败明确列出路线与动作。
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NodeRecoveryBar from '../NodeRecoveryBar.vue'
import type { UnresolvedFailure } from '@/features/workspace/api/agentRuns'

function failure(overrides: Partial<UnresolvedFailure> & { runId: string }): UnresolvedFailure {
  return {
    projectId: 'p-1',
    operation: 'ANSWER_TIP',
    routeId: 'r-1',
    sourceNodeId: 'n-1',
    reasonCode: 'brain_timeout',
    reasonSummary: '模型服务响应超时，本次生成未完成，请稍后重试',
    availableAction: 'CONTINUE_PROCESSING',
    actionLabel: '继续处理',
    stale: false,
    retryRunId: null,
    retryStatus: null,
    createdAt: '2026-09-27T00:00:00Z',
    ...overrides,
  }
}

function mountBar(items: Parameters<typeof Object>[0][] extends never ? never : any, retrying: (id: string) => boolean = () => false, extraProps: Record<string, unknown> = {}) {
  return mount(NodeRecoveryBar, {
    props: {
      items,
      isRetrying: retrying,
      ...extraProps,
    } as never,
    global: { plugins: [createPinia()] },
  })
}

describe('NodeRecoveryBar', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders reason and a 32px round icon with full aria contract', async () => {
    const wrapper = mountBar([
      { failure: failure({ runId: 'f-1' }), routeLabel: '主路线' },
    ])
    expect(wrapper.find('[data-test="node-recovery-reason"]').text()).toContain('模型服务响应超时')
    const button = wrapper.find('[data-test="node-recovery-retry"]')
    expect(button.attributes('type')).toBe('button')
    expect(button.attributes('aria-label')).toBe('继续处理（主路线）')
    expect(button.attributes('title')).toBe('继续处理（主路线）')
    expect(button.classes()).toContain('nodrag')
    await button.trigger('click')
    expect(wrapper.emitted('retry')?.[0]).toEqual([failure({ runId: 'f-1' })])
  })

  it('disables the icon while a retry is in flight', () => {
    const wrapper = mountBar(
      [{ failure: failure({ runId: 'f-1' }) }],
      (id) => id === 'f-1',
    )
    const button = wrapper.find('[data-test="node-recovery-retry"]')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('◌')
  })

  it('routes model config failures to the settings entry', async () => {
    const wrapper = mountBar([
      { failure: failure({
        runId: 'f-2',
        reasonCode: 'model_provider_failure',
        availableAction: 'GO_TO_MODEL_SETTINGS',
        actionLabel: '前往模型设置',
      }) },
    ])
    expect(wrapper.find('[data-test="node-recovery-retry"]').exists()).toBe(false)
    await wrapper.find('[data-test="node-recovery-settings"]').trigger('click')
    expect(wrapper.emitted('go-settings')).toHaveLength(1)
  })

  it('offers locate (not retry) for stale targets', async () => {
    const stale = failure({ runId: 'f-3', availableAction: 'STALE', actionLabel: '查看变化' })
    const wrapper = mountBar([{ failure: stale }])
    await wrapper.find('[data-test="node-recovery-locate"]').trigger('click')
    expect(wrapper.emitted('locate')?.[0]).toEqual([stale])
    expect(wrapper.emitted('retry')).toBeUndefined()
  })

  it('lists multiple unresolved failures with route labels instead of guessing one target', async () => {
    const wrapper = mountBar([
      { failure: failure({ runId: 'f-1', routeId: 'r-1' }), routeLabel: '主路线' },
      { failure: failure({ runId: 'f-2', routeId: 'r-2', availableAction: 'RETRY_SPEC', actionLabel: '重试生成规格' }), routeLabel: '分支B' },
    ])
    expect(wrapper.find('[data-test="node-recovery-retry"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="node-recovery-toggle"]').text()).toContain('2 项待处理')
    await wrapper.find('[data-test="node-recovery-toggle"]').trigger('click')
    const list = wrapper.find('[data-test="node-recovery-list"]')
    expect(list.text()).toContain('主路线 · 继续处理')
    expect(list.text()).toContain('分支B · 重试生成规格')
    await wrapper.find('[data-test="node-recovery-retry-f-2"]').trigger('click')
    expect(wrapper.emitted('retry')?.[0]).toEqual([
      failure({ runId: 'f-2', routeId: 'r-2', availableAction: 'RETRY_SPEC', actionLabel: '重试生成规格' }),
    ])
  })

  it('renders nothing without failures', () => {
    const wrapper = mountBar([])
    expect(wrapper.find('[data-test="node-recovery-bar"]').exists()).toBe(false)
  })

  // 顶部恢复汇总(locateOnly)的契约:组件内真实不渲染任何重试/恢复控件
  // (不是隐藏,不是只解除监听),单项只有"查看",多项每项只有"定位"。
  describe('locateOnly (top summary banner)', () => {
    it('single failure offers 查看 only and never emits retry or go-settings', async () => {
      const wrapper = mountBar(
        [{ failure: failure({ runId: 'f-1' }), routeLabel: '主路线' }],
        () => false,
        { locateOnly: true },
      )
      expect(wrapper.find('[data-test="node-recovery-retry"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="node-recovery-locate"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="node-recovery-settings"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="pending-recovery-locate"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="pending-recovery-reason"]').text()).toContain('主路线')
      // 配置类失败在顶部也只定位:真正的设置入口留在节点/检查器
      const configFailure = failure({
        runId: 'f-9',
        availableAction: 'GO_TO_MODEL_SETTINGS',
        actionLabel: '前往模型设置',
      })
      const configWrapper = mountBar(
        [{ failure: configFailure, routeLabel: '主路线' }],
        () => false,
        { locateOnly: true },
      )
      await configWrapper.find('[data-test="pending-recovery-locate"]').trigger('click')
      expect(configWrapper.emitted('locate')?.[0]).toEqual([configFailure])
      expect(configWrapper.emitted('go-settings')).toBeUndefined()
      expect(configWrapper.emitted('retry')).toBeUndefined()
      expect(wrapper.emitted('retry')).toBeUndefined()
    })

    it('multiple failures expand to route + reason rows, each with locate only', async () => {
      const wrapper = mountBar(
        [
          { failure: failure({ runId: 'f-1', routeId: 'r-1' }), routeLabel: '主路线' },
          { failure: failure({ runId: 'f-2', routeId: 'r-2', availableAction: 'RETRY_SPEC', actionLabel: '重试生成规格' }), routeLabel: '分支B' },
        ],
        () => false,
        { locateOnly: true },
      )
      expect(wrapper.text()).toContain('2 项待处理')
      await wrapper.find('[data-test="pending-recovery-toggle"]').trigger('click')
      const list = wrapper.find('[data-test="pending-recovery-list"]')
      expect(list.text()).toContain('主路线')
      expect(list.text()).toContain('分支B')
      expect(list.find('[data-test="node-recovery-retry-f-2"]').exists()).toBe(false)
      await wrapper.find('[data-test="pending-recovery-locate-f-2"]').trigger('click')
      expect(wrapper.emitted('locate')?.[0]?.[0]).toMatchObject({ runId: 'f-2' })
      expect(wrapper.emitted('retry')).toBeUndefined()
    })

    it('keyboard activation of the locate button works (native button + focus)', async () => {
      const wrapper = mountBar(
        [{ failure: failure({ runId: 'f-1' }), routeLabel: '主路线' }],
        () => false,
        { locateOnly: true },
      )
      const button = wrapper.find('[data-test="pending-recovery-locate"]')
      expect((button.element as HTMLButtonElement).disabled).toBe(false)
      await button.trigger('keydown.enter')
      await button.trigger('click')
      expect(wrapper.emitted('locate')).toHaveLength(1)
    })
  })
})
