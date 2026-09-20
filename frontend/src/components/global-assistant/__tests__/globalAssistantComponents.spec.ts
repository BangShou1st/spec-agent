import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AssistantComposer from '@/components/global-assistant/AssistantComposer.vue'
import AssistantMessage from '@/components/global-assistant/AssistantMessage.vue'
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

  it('renders icon-only send and stop controls with accessible labels', () => {
    const idle = mount(AssistantComposer, { props: { running: false, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: 'hi' } })
    expect(idle.find('[data-test="ga-send"]').classes()).toContain('ga-composer__icon-btn')
    expect(idle.find('[data-test="ga-send"]').attributes('aria-label')).toBe('发送')
    expect(idle.find('[data-test="ga-stop"]').exists()).toBe(false)

    const running = mount(AssistantComposer, { props: { running: true, sending: false, cancelRequested: false, waitingQuestion: null, modelValue: '' } })
    expect(running.find('[data-test="ga-stop"]').classes()).toContain('ga-composer__icon-btn')
    expect(running.find('[data-test="ga-stop"]').attributes('aria-label')).toBe('停止')
    expect(running.find('[data-test="ga-send"]').attributes('aria-label')).toBe('发送调整')
  })

  it('no longer repeats the last reply above the input', () => {
    const wrapper = mount(AssistantComposer, { props: { running: false, sending: false, cancelRequested: false, waitingQuestion: '需要我继续吗？', modelValue: '' } })
    expect(wrapper.find('[data-test="ga-clarification"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('需要我继续吗？')
  })
});

describe('tool activity', () => {
  it('renders running, success and failure states', () => {
    const base = { key: 'k', capabilityId: 'project.search', displayName: '搜索项目', summary: null, argsSummary: null, startedAt: '2026-01-01T00:00:00Z', endedAt: null, durationMs: null, resourceRefs: [] as { kind: string; id: string; label: string }[], resultKind: null, resultCount: null }
    const running = mount(ToolActivityItem, { props: { activity: { ...base, state: 'running' } } })
    expect(running.attributes('data-state')).toBe('running')
    const success = mount(ToolActivityItem, { props: { activity: { ...base, state: 'success', summary: 'Found 2' } } })
    expect(success.text()).toContain('Found 2')
    const failure = mount(ToolActivityItem, { props: { activity: { ...base, state: 'failure', summary: '失败' } } })
    expect(failure.attributes('data-state')).toBe('failure')
  })
});

describe('assistant message timestamps', () => {
  it('shows only the clock for today and a real date for older messages', () => {
    const now = new Date()
    const today = mount(AssistantMessage, { props: { role: 'USER', content: 'hi', createdAt: now.toISOString() } })
    expect(today.find('.ga-message__time').exists()).toBe(true)
    expect(today.find('.ga-message__time').text()).toMatch(/^\d{2}:\d{2}$/)

    const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1, 17, 53)
    const older = mount(AssistantMessage, { props: { role: 'ASSISTANT', content: 'ok', createdAt: yesterday.toISOString() } })
    expect(older.find('.ga-message__time').text()).toBe('昨天 17:53')
  })

  it('hides the timestamp row when the backend sends no usable time', () => {
    const wrapper = mount(AssistantMessage, { props: { role: 'ASSISTANT', content: 'ok', createdAt: null } })
    expect(wrapper.find('.ga-message__time').exists()).toBe(false)
  })

  it('shows provider and model attribution after the time on assistant messages', () => {
    const attributed = mount(AssistantMessage, {
      props: { role: 'ASSISTANT', content: 'ok', createdAt: '2026-01-01T00:00:00Z', providerLabel: 'OpenCode Zen', modelId: 'mimo-v2.5-free' },
    })
    expect(attributed.find('[data-test="ga-message-attribution"]').text()).toBe('OpenCode Zen · mimo-v2.5-free')

    // USER messages and un-attributed assistant messages stay bare.
    const user = mount(AssistantMessage, {
      props: { role: 'USER', content: 'hi', createdAt: '2026-01-01T00:00:00Z', providerLabel: 'OpenCode Zen', modelId: 'mimo-v2.5-free' },
    })
    expect(user.find('[data-test="ga-message-attribution"]').exists()).toBe(false)

    const bare = mount(AssistantMessage, { props: { role: 'ASSISTANT', content: 'ok', createdAt: '2026-01-01T00:00:00Z' } })
    expect(bare.find('[data-test="ga-message-attribution"]').exists()).toBe(false)
  })
});

describe('conversation timeline', () => {
  it('emits the starter prompt when an empty-state chip is clicked', async () => {
    const empty = mount(ConversationTimeline, { props: { messages: [], activities: [], streamingText: '', currentStatus: null, running: false, waitingQuestion: null } })
    const chips = empty.findAll('[data-test="ga-empty-chip"]')
    expect(chips).toHaveLength(3)
    await chips[0].trigger('click')
    expect(empty.emitted('suggestion')?.[0]).toEqual(['帮我找一下正在做的项目'])
    await chips[2].trigger('click')
    expect(empty.emitted('suggestion')?.[1]).toEqual(['打开项目列表'])
  })

  it('renders empty, messages, tools and clarification', () => {
    const empty = mount(ConversationTimeline, { props: { messages: [], activities: [], streamingText: '', currentStatus: null, running: false, waitingQuestion: null } })
    expect(empty.find('[data-test="ga-empty"]').exists()).toBe(true)
    const full = mount(ConversationTimeline, {
      props: {
        messages: [{ id: 'm-1', threadId: 't-1', role: 'USER', content: 'hi', runId: null, createdAt: '2026-01-01T00:00:00Z' }],
        activities: [{ key: 'k1', capabilityId: 'project.search', displayName: '搜索项目', state: 'running', summary: null, argsSummary: null, startedAt: '2026-01-01T00:00:00Z', endedAt: null, durationMs: null, resourceRefs: [], resultKind: null, resultCount: null }],
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

  it('renders the waiting clarification question when the run asks for input', () => {
    const wrapper = mount(ConversationTimeline, {
      props: {
        messages: [{ id: 'm-1', threadId: 't-1', role: 'USER', content: '找项目', runId: 'r-1', createdAt: '2026-01-01T00:00:00Z' }],
        activities: [],
        streamingText: '',
        currentStatus: null,
        running: false,
        waitingQuestion: '你指的是哪个项目？请补充说明。',
      },
    })
    expect(wrapper.find('[data-test="ga-clarification"]').text()).toContain('你指的是哪个项目？请补充说明。')

    const none = mount(ConversationTimeline, { props: { messages: [], activities: [], streamingText: '', currentStatus: null, running: false, waitingQuestion: null } })
    expect(none.find('[data-test="ga-clarification"]').exists()).toBe(false)
  })
});

describe('assistant panel open and failed states', () => {
  it('fills the composer with the starter prompt from the empty state', async () => {
    setupPinia()
    const router = await setupRouter()
    const store = useGlobalAssistantStore()
    store.panelOpen = true
    const wrapper = mount(AssistantPanel, { global: { plugins: [router] } })
    await wrapper.find('[data-test="ga-empty-chip"]').trigger('click')
    expect((wrapper.find('[data-test="ga-composer-input"]').element as HTMLTextAreaElement).value)
      .toBe('帮我找一下正在做的项目')
  })

  it('shows failed run and cancel affordance wiring', async () => {
    setupPinia()
    const router = await setupRouter()
    const store = useGlobalAssistantStore()
    store.panelOpen = true
    store.error = { code: 'MODEL_UNAVAILABLE', message: '模型服务暂时不可用，请稍后再试' }
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
