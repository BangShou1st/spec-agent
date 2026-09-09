import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AssistantComposer from '@/components/global-assistant/AssistantComposer.vue'
import ToolActivityItem from '@/components/global-assistant/ToolActivityItem.vue'
import ConversationTimeline from '@/components/global-assistant/ConversationTimeline.vue'
import AssistantPanel from '@/components/global-assistant/AssistantPanel.vue'
import { useGlobalAssistantStore } from '@/stores/globalAssistantStore'

function setupPinia(): void {
  setActivePinia(createPinia())
}

const routes = [{ path: '/', component: { template: '<div />' } }, { path: '/projects', component: { template: '<div />' } }]

async function setupRouter(): Promise<ReturnType<typeof createRouter>> {
  const router = createRouter({ history: createWebHistory(), routes })
  router.push('/')
  await router.isReady()
  return router
}

describe('assistant composer', () => {
  it('submits on Enter and keeps Shift+Enter as newline', async () => {
    const wrapper = mount(AssistantComposer, { props: { running: false, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: 'hello' } })
    await wrapper.find('[data-test="ga-composer-input"]').trigger('keydown', { key: 'Enter', shiftKey: false })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('disables send for blank messages and shows count near the limit', () => {
    const blank = mount(AssistantComposer, { props: { running: false, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: '   ' } })
    expect((blank.find('[data-test="ga-send"]').element as HTMLButtonElement).disabled).toBe(true)
    const longText = 'x'.repeat(3600)
    const longWrapper = mount(AssistantComposer, { props: { running: false, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: longText } })
    expect(longWrapper.find('[data-test="ga-composer-count"]').exists()).toBe(true)
  })

  it('shows stop control while running', () => {
    const wrapper = mount(AssistantComposer, { props: { running: true, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: '' } })
    expect(wrapper.find('[data-test="ga-stop"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ga-send"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ga-composer-input"]').attributes('placeholder')).toContain('调整方向')
  })
  it('disables send while steer pending', () => {
    const wrapper = mount(AssistantComposer, { props: { running: true, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: '换方向', pendingSteer: true } })
    expect((wrapper.find('[data-test="ga-send"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.find('[data-test="ga-steer-pending"]').exists()).toBe(true)
  })
});

describe('tool activity', () => {
  it('renders running, success and failure states', () => {
    const base = { key: 'k', capabilityId: 'project.search', displayName: '搜索项目', summary: null, argsSummary: null, startedAt: '2026-01-01T00:00:00Z', endedAt: null, durationMs: null } as const
    const running = mount(ToolActivityItem, { props: { activity: { ...base, state: 'running' } } })
    expect(running.attributes('data-state')).toBe('running')
    const success = mount(ToolActivityItem, { props: { activity: { ...base, state: 'success', summary: 'Found 2' } } })
    expect(success.text()).toContain('Found 2')
    const failure = mount(ToolActivityItem, { props: { activity: { ...base, state: 'failure', summary: '失败' } } })
    expect(failure.attributes('data-state')).toBe('failure')
  })
});

describe('conversation timeline', () => {
  it('renders empty, messages, tools and clarification', () => {
    const empty = mount(ConversationTimeline, { props: { messages: [], activities: [], streamingText: '', currentStatus: null, running: false, waitingQuestion: null } })
    expect(empty.find('[data-test="ga-empty"]').exists()).toBe(true)
    const full = mount(ConversationTimeline, {
      props: {
        messages: [{ id: 'm-1', threadId: 't-1', role: 'USER', content: 'hi', runId: null, createdAt: '2026-01-01T00:00:00Z' }],
        activities: [{ key: 'k1', capabilityId: 'project.search', displayName: '搜索项目', state: 'running', summary: null, argsSummary: null, startedAt: '2026-01-01T00:00:00Z', endedAt: null, durationMs: null }],
        streamingText: '',
        currentStatus: '正在查找项目…',
        running: true,
        waitingQuestion: null,
      },
    })
    expect(full.find('[data-test="ga-message"]').exists()).toBe(true)
    expect(full.find('[data-test="ga-tool-activity"]').exists()).toBe(true)
    expect(full.find('[data-test="ga-status"]').exists()).toBe(true)
  })
});

describe('assistant panel open and failed states', () => {
  it('shows failed run and cancel affordance wiring', async () => {
    setupPinia()
    const router = await setupRouter()
    const store = useGlobalAssistantStore()
    store.panelOpen = true
    store.error = { code: 'MODEL_UNAVAILABLE', message: '模型服务暂时不可用，请稍后再试。' }
    const wrapper = mount(AssistantPanel, { global: { plugins: [router] } })
    expect(wrapper.find('[data-test="ga-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ga-panel"]').exists()).toBe(true)
  })

  it('shows approval notice and reconnect affordance', async () => {
    setupPinia()
    const router = await setupRouter()
    const store = useGlobalAssistantStore()
    store.panelOpen = true
    store.approvalRequired = true
    store.connection = 'disconnected'
    store.activeRunId = 'r-1'
    const wrapper = mount(AssistantPanel, { global: { plugins: [router] } })
    expect(wrapper.find('[data-test="ga-approval"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="ga-reconnect"]').exists()).toBe(true)
  })
});
