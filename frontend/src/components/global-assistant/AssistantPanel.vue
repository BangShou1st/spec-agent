<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConversationTimeline from './ConversationTimeline.vue'
import AssistantComposer from './AssistantComposer.vue'
import ConversationHistory from './ConversationHistory.vue'
import AppIcon from '@/components/AppIcon.vue'
import { buildGaUiContext } from '@/api/globalAssistant'
import { useGlobalAssistantStore } from '@/stores/globalAssistantStore'
import { currentGaTitle } from '@/presentation/conversationLibrary'

const store = useGlobalAssistantStore()
const route = useRoute()
const router = useRouter()
const composerText = ref('')
const composerRef = ref<InstanceType<typeof AssistantComposer> | null>(null)

const running = computed(() => store.isRunning)
const isReconnecting = computed(() => store.connection === 'reconnecting')
const isDisconnected = computed(() => store.connection === 'disconnected')
const historyOpen = computed(() => store.historyOpen)
const switchGuard = computed(() => running.value || store.sending || store.steerSending)
const pendingSteer = computed(() => store.pendingSteer)
const stoppedNotice = computed(() => store.stoppedNotice)
const currentTitle = computed(() => currentGaTitle(store.threadId, store.threads, '新对话'))

function handleReconnect(): void {
  store.retryConnection()
}

onMounted(() => {
  void store.init().then(() => {
    if (store.draft) composerText.value = store.draft
  })
})

watch(
  () => store.pendingNavigation,
  async (target) => {
    if (!target) return
    const current = route.path
    store.consumeNavigation()
    if (current === target) return
    try {
      await router.push(target)
    } catch {
      /* navigation is best-effort; thread stays alive */
    }
  },
)

watch(
  () => store.waitingQuestion,
  (question) => {
    if (question) composerRef.value?.focusComposer()
  },
)

watch(
  () => store.draft,
  (draft) => {
    if (draft && !composerText.value) composerText.value = draft
  },
)

async function handleSend(): Promise<void> {
  const text = composerText.value
  if (!text.trim()) return
  const uiContext = buildGaUiContext({ path: route.path, params: route.params as Record<string, string> })
  composerText.value = ''
  await store.sendMessage(text, uiContext)
  if (store.draft) composerText.value = store.draft
}

function handleCancel(): void {
  void store.cancelActiveRun()
}

function handleNewConversation(): void {
  if (switchGuard.value) return
  void store.startNewConversation()
}

function handleToggleHistory(): void {
  store.toggleHistory()
}

function handleSelectThread(threadId: string): void {
  void store.switchThread(threadId)
}
function handleConfirmDelete(threadId: string): void {
  void store.deleteThread(threadId)
}

function handleRetryHistory(): void {
  void store.loadThreads()
}

function handleClose(): void {
  store.setPanelOpen(false)
}
</script>

<template>
  <section
    class="ga-panel"
    role="complementary"
    aria-label="Spec Agent 全局助手"
    data-test="ga-panel"
    :data-state="store.panelOpen ? 'open' : 'closed'"
  >
    <header class="ga-panel__header">
      <button
        class="icon-btn ga-panel__history-btn"
        type="button"
        data-test="ga-history-toggle"
        aria-label="查看最近对话"
        title="最近对话"
        :aria-expanded="historyOpen ? 'true' : 'false'"
        aria-controls="ga-history-region"
        @click="handleToggleHistory"
      >
        <AppIcon name="history" />
      </button>
      <div class="ga-panel__title" data-test="ga-current-conversation" :title="currentTitle">
        <strong class="ga-panel__name">助手</strong>
        <span class="ga-panel__current">{{ currentTitle }}</span>
        <span v-if="running" class="ga-panel__run-dot" aria-hidden="true" />
      </div>
      <div class="ga-panel__actions">
        <button
          class="icon-btn"
          type="button"
          data-test="ga-new-conversation"
          aria-label="开始新对话"
          title="开始新对话"
          :disabled="switchGuard"
          @click="handleNewConversation"
        >
          <AppIcon name="plus" />
        </button>
        <button
          class="icon-btn"
          type="button"
          data-test="ga-close"
          aria-label="关闭助手"
          @click="handleClose"
        >
          <AppIcon name="close" />
        </button>
      </div>
    </header>

    <div v-if="isReconnecting" class="ga-panel__connection" data-test="ga-connection">重新连接中…</div>
    <div v-else-if="isDisconnected" class="ga-panel__connection ga-panel__connection--disconnected" data-test="ga-connection">
      <span>连接已断开，任务仍在后台继续</span>
      <button class="ga-panel__reconnect" type="button" data-test="ga-reconnect" @click="handleReconnect">重新连接</button>
    </div>
    <div v-if="store.approvalRequired" class="ga-panel__approval" data-test="ga-approval">该步骤需要批准，当前版本暂不支持审批操作。</div>

    <div v-if="store.error" class="ga-panel__error" role="alert" data-test="ga-error">
      <span>{{ store.error.message }}</span>
      <button class="icon-btn" type="button" aria-label="关闭错误提示" @click="store.error = null"><AppIcon name="close" /></button>
    </div>

    <div class="ga-panel__status-live visually-hidden" aria-live="polite" atomic="true">
      <span v-if="store.currentStatus">{{ store.currentStatus }}</span>
      <span v-else-if="running">助手正在处理</span>
    </div>

    <p v-if="store.loadingThread" class="ga-panel__loading muted" data-test="ga-loading">正在恢复会话…</p>

    <div v-if="historyOpen" id="ga-history-region" class="ga-panel__history-wrap">
      <ConversationHistory
        :threads="store.threads"
        :current-thread-id="store.threadId"
        :loading="store.threadsLoading"
        :error="store.threadsError"
        :disabled="switchGuard"
        :switching="store.switchingThread"
        :active-thread-id="store.isRunning ? store.threadId : null"
        :deleting-thread-id="store.deletingThreadId"
        :confirm-delete-thread-id="store.confirmDeleteThreadId"
        @select="handleSelectThread"
        @retry="handleRetryHistory"
        @request-delete="store.confirmDeleteThreadId = $event"
        @cancel-delete="store.confirmDeleteThreadId = null"
        @confirm-delete="handleConfirmDelete"
      />
    </div>
    <ConversationTimeline
      v-if="!historyOpen"
      :messages="store.messages"
      :activities="store.activities"
      :streaming-text="store.streamingText"
      :current-status="store.currentStatus"
      :running="running"
      :waiting-question="store.waitingQuestion"
      :pending-steer="pendingSteer"
      :stopped-notice="stoppedNotice"
    />

    <AssistantComposer
      ref="composerRef"
      v-model="composerText"
      :running="running"
      :sending="store.sending"
      :cancel-requested="store.cancelRequested"
      :waiting-question="store.waitingQuestion"
      :pending-steer="!!pendingSteer"
      :steer-sending="store.steerSending"
      @send="handleSend"
      @cancel="handleCancel"
    />
  </section>
</template>

<style scoped>
.ga-panel { display: flex; flex-direction: column; height: 100%; min-height: 0; background: linear-gradient(180deg, #fafbff 0%, var(--color-surface) 28%, var(--color-surface) 100%); }
.ga-panel__header { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--color-border); background: linear-gradient(135deg, rgba(99,102,241,0.10), rgba(168,85,247,0.08) 45%, rgba(255,255,255,0.9)); backdrop-filter: blur(8px); }
.ga-panel__history-btn[aria-expanded='true'] { background: var(--color-focus-soft); color: var(--color-focus-strong); box-shadow: inset 0 0 0 1px var(--color-focus); }
.ga-panel__title { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; }
.ga-panel__name { font-size: 14px; font-weight: 750; letter-spacing: 0.01em; background: linear-gradient(135deg, #312e81, #7c3aed); -webkit-background-clip: text; background-clip: text; color: transparent; }
.ga-panel__current { font-size: 12px; color: var(--color-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 160px; background: rgba(255,255,255,0.7); border: 1px solid var(--color-border); padding: 2px 8px; border-radius: 999px; }
.ga-panel__run-dot { width: 8px; height: 8px; border-radius: 999px; background: linear-gradient(135deg, var(--color-accent), #a855f7); box-shadow: 0 0 0 4px var(--color-accent-soft); animation: ga-pulse 1.6s ease-in-out infinite; flex: none; }
.ga-panel__actions { display: flex; gap: 4px; }
.ga-panel__history-wrap { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.ga-panel__connection { padding: 8px 12px; font-size: 12px; color: var(--color-warn); background: linear-gradient(135deg, var(--color-warn-soft), rgba(255,255,255,0.6)); border-bottom: 1px solid var(--color-border); display: flex; align-items: center; gap: 8px; }
.ga-panel__reconnect { margin-left: auto; border: 1px solid var(--color-border); background: var(--color-surface); border-radius: 999px; padding: 4px 12px; font-size: 12px; box-shadow: 0 2px 8px -4px rgba(0,0,0,0.2); }
.ga-panel__reconnect:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.ga-panel__approval { margin: 8px 12px 0; padding: 10px 12px; border-radius: 12px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); color: var(--color-text-secondary); font-size: 13px; }
.ga-panel__error { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 8px 12px 0; padding: 10px 12px; border-radius: 12px; background: linear-gradient(135deg, var(--color-danger-soft), rgba(255,255,255,0.7)); border: 1px solid #ecc0bc; color: var(--color-danger); font-size: 13px; box-shadow: 0 6px 18px -10px rgba(190,40,40,0.4); }
.ga-panel__loading { padding: 16px 12px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
@keyframes ga-pulse { 0%,100% { transform: scale(1); opacity: 1; } 50% { transform: scale(0.85); opacity: 0.75; } }
</style>
