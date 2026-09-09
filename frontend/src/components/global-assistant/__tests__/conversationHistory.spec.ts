import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ConversationHistory from '@/components/global-assistant/ConversationHistory.vue'
import AssistantPanel from '@/components/global-assistant/AssistantPanel.vue'
import { useGlobalAssistantStore } from '@/stores/globalAssistantStore'

const routes = [{ path: '/', component: { template: '<div />' } }]

async function setupRouter(): Promise<ReturnType<typeof createRouter>> {
  const router = createRouter({ history: createWebHistory(), routes })
  router.push('/')
  await router.isReady()
  return router
}

function threads(): Array<{ threadId: string; title: string; preview: string; updatedAt: string; createdAt: string }> {
  return [
    { threadId: 't-new', title: '新会话', preview: '新预览', updatedAt: new Date().toISOString(), createdAt: new Date().toISOString() },
    { threadId: 't-old', title: '旧会话', preview: '旧预览', updatedAt: '2026-08-01T10:00:00Z', createdAt: '2026-08-01T09:00:00Z' },
  ]
}

describe('conversation history component', () => {
  it('renders rows grouped with current highlight', () => {
    const wrapper = mount(ConversationHistory, {
      props: { threads: threads(), currentThreadId: 't-old', loading: false, error: null, disabled: false, switching: false },
    })
    expect(wrapper.find('[data-test="ga-history"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="ga-history-item"]')).toHaveLength(2)
    expect(wrapper.find('[data-thread="t-old"]').attributes('data-current')).toBe('true')
    expect(wrapper.find('[data-test="ga-history-current"]').exists()).toBe(true)
  })

  it('shows empty history calmly', () => {
    const wrapper = mount(ConversationHistory, {
      props: { threads: [], currentThreadId: null, loading: false, error: null, disabled: false, switching: false },
    })
    expect(wrapper.find('[data-test="ga-history-empty"]').exists()).toBe(true)
  })

  it('disables rows while a run is active', () => {
    const wrapper = mount(ConversationHistory, {
      props: { threads: threads(), currentThreadId: 't-new', loading: false, error: null, disabled: true, switching: false },
    })
    expect(wrapper.find('[data-test="ga-switch-guard"]').exists()).toBe(true)
    const row = wrapper.find('[data-thread="t-old"]')
    expect((row.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('emits select when switching conversation', async () => {
    const wrapper = mount(ConversationHistory, {
      props: { threads: threads(), currentThreadId: 't-new', loading: false, error: null, disabled: false, switching: false },
    })
    await wrapper.find('[data-thread="t-old"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['t-old'])
  })
})

describe('assistant panel history integration', () => {
  it('opens and closes history without closing the assistant', async () => {
    setActivePinia(createPinia())
    const router = await setupRouter()
    const store = useGlobalAssistantStore()
    store.panelOpen = true
    store.threadId = 't-new'
    store.threads = threads()
    const wrapper = mount(AssistantPanel, { global: { plugins: [router] } })
    expect(wrapper.find('[data-test="ga-history-toggle"]').exists()).toBe(true)
    await wrapper.find('[data-test="ga-history-toggle"]').trigger('click')
    expect(store.historyOpen).toBe(true)
    expect(wrapper.find('[data-test="ga-history"]').exists()).toBe(true)
    await wrapper.find('[data-test="ga-history-toggle"]').trigger('click')
    expect(store.historyOpen).toBe(false)
    expect(wrapper.find('[data-test="ga-panel"]').exists()).toBe(true)
  })

  it('disables new conversation while running', async () => {
    setActivePinia(createPinia())
    const router = await setupRouter()
    const store = useGlobalAssistantStore()
    store.panelOpen = true
    store.activeRunId = 'r-1'
    store.activeStatus = 'RUNNING'
    const wrapper = mount(AssistantPanel, { global: { plugins: [router] } })
    expect((wrapper.find('[data-test="ga-new-conversation"]').element as HTMLButtonElement).disabled).toBe(true)
  })
})
