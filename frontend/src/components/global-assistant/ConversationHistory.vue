<script setup lang="ts">
import { computed } from 'vue'
import type { GaThreadListItem } from '@/api/globalAssistant'
import { formatGaRelativeTime, groupGaThreads } from '@/presentation/conversationLibrary'
import GaIcon from './GaIcon.vue'

const props = defineProps<{
  threads: GaThreadListItem[]
  currentThreadId: string | null
  loading: boolean
  error: string | null
  disabled: boolean
  switching: boolean
}>()

const emit = defineEmits<{
  (e: 'select', threadId: string): void
  (e: 'retry'): void
}>()

const groups = computed(() => groupGaThreads(props.threads, new Date()))

function isCurrent(threadId: string): boolean {
  return !!props.currentThreadId && props.currentThreadId === threadId
}

function handleSelect(threadId: string): void {
  if (props.disabled || props.switching) return
  if (isCurrent(threadId)) return
  emit('select', threadId)
}
</script>

<template>
  <div class="ga-history" data-test="ga-history" role="region" aria-label="最近对话">
    <p class="ga-history__eyebrow">最近对话</p>
    <p v-if="disabled" class="ga-history__guard" data-test="ga-switch-guard">
      请先等待当前任务完成或停止任务，再切换或开始新会话。
    </p>
    <p v-if="loading" class="ga-history__state muted" data-test="ga-history-loading">正在加载历史会话…</p>
    <div v-else-if="error" class="ga-history__state ga-history__error" data-test="ga-history-error" role="alert">
      <span>{{ error }}</span>
      <button class="ga-history__retry" type="button" data-test="ga-history-retry" @click="emit('retry')">重试</button>
    </div>
    <p v-else-if="groups.length === 0" class="ga-history__state muted" data-test="ga-history-empty">
      暂无历史会话，发送第一条消息后会自动收录。
    </p>
    <div v-else class="ga-history__groups">
      <section
        v-for="group in groups"
        :key="group.key"
        class="ga-history__group"
        data-test="ga-history-group"
        :data-group="group.key"
      >
        <h3 class="ga-history__group-title">{{ group.label }}</h3>
        <ul class="ga-history__list">
          <li v-for="item in group.items" :key="item.threadId">
            <button
              class="ga-history__row"
              type="button"
              data-test="ga-history-item"
              :data-thread="item.threadId"
              :data-current="isCurrent(item.threadId) ? 'true' : 'false'"
              :aria-current="isCurrent(item.threadId) ? 'true' : undefined"
              :disabled="disabled || switching || isCurrent(item.threadId)"
              @click="handleSelect(item.threadId)"
            >
              <span class="ga-history__row-main">
                <span class="ga-history__row-title">{{ item.title || '未命名对话' }}</span>
                <span class="ga-history__row-preview">{{ item.preview }}</span>
              </span>
              <span class="ga-history__row-meta">
                <time class="ga-history__time">{{ formatGaRelativeTime(item.updatedAt) }}</time>
                <span
                  v-if="isCurrent(item.threadId)"
                  class="ga-history__current"
                  data-test="ga-history-current"
                >
                  <GaIcon name="check" />
                  <span class="visually-hidden">当前会话</span>
                </span>
              </span>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.ga-history {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  background: var(--color-surface);
}
.ga-history__eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--color-text-muted);
}
.ga-history__guard {
  margin: 0 0 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--color-warn);
  background: var(--color-warn-soft);
  border: 1px solid var(--color-border);
  border-radius: 8px;
}
.ga-history__state {
  margin: 12px 2px;
  font-size: 13px;
}
.ga-history__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--color-danger);
}
.ga-history__retry {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 12px;
}
.ga-history__group {
  margin-bottom: 12px;
}
.ga-history__group:last-child {
  margin-bottom: 2px;
}
.ga-history__group-title {
  margin: 0 0 4px;
  padding: 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-muted);
}
.ga-history__list {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--color-border);
}
.ga-history__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 9px 8px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.ga-history__row:hover:not(:disabled) {
  background: var(--color-surface-subtle);
}
.ga-history__row:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: 6px;
}
.ga-history__row:disabled {
  cursor: default;
}
.ga-history__row[data-current='true'] {
  background: var(--color-focus-soft);
}
.ga-history__row-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.ga-history__row-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ga-history__row-preview {
  font-size: 12px;
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ga-history__row-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.ga-history__time {
  font-size: 12px;
  color: var(--color-text-muted);
  white-space: nowrap;
}
.ga-history__current {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  color: var(--color-focus-strong);
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
@media (prefers-reduced-motion: reduce) {
  .ga-history__row {
    transition: none;
  }
}
</style>
