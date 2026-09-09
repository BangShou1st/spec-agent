<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GaThreadListItem } from '@/api/globalAssistant'
import { formatGaRelativeTime, groupGaThreads } from '@/presentation/conversationLibrary'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps<{
  threads: GaThreadListItem[]
  currentThreadId: string | null
  loading: boolean
  error: string | null
  disabled: boolean
  switching: boolean
  activeThreadId?: string | null
  deletingThreadId?: string | null
  confirmDeleteThreadId?: string | null
}>()

const emit = defineEmits<{
  (e: 'select', threadId: string): void
  (e: 'retry'): void
  (e: 'request-delete', threadId: string): void
  (e: 'cancel-delete'): void
  (e: 'confirm-delete', threadId: string): void
}>()

const openMenuId = ref<string | null>(null)

const groups = computed(() => groupGaThreads(props.threads, new Date()))

function isCurrent(threadId: string): boolean {
  return !!props.currentThreadId && props.currentThreadId === threadId
}

function isActiveThread(threadId: string): boolean {
  return !!props.activeThreadId && props.activeThreadId === threadId
}

function canSwitch(): boolean {
  return !props.disabled && !props.switching
}

function handleSelect(threadId: string): void {
  if (!canSwitch() || isCurrent(threadId)) return
  emit('select', threadId)
}

function toggleMenu(threadId: string): void {
  openMenuId.value = openMenuId.value === threadId ? null : threadId
}

function closeMenu(): void {
  openMenuId.value = null
}

function onMenuKeydown(event: KeyboardEvent, threadId: string): void {
  if (event.key === 'Escape') {
    event.stopPropagation()
    closeMenu()
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
  }
  void threadId
}
</script>

<template>
  <div class="ga-history" data-test="ga-history" role="region" aria-label="最近对话">
    <p class="ga-history__eyebrow">最近对话</p>
    <p v-if="disabled" class="ga-history__guard" data-test="ga-switch-guard">当前任务完成或停止后可以切换会话。</p>
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
          <li v-for="item in group.items" :key="item.threadId" class="ga-history__item">
            <button
              class="ga-history__row"
              type="button"
              data-test="ga-history-item"
              :data-thread="item.threadId"
              :data-current="isCurrent(item.threadId) ? 'true' : 'false'"
              :aria-current="isCurrent(item.threadId) ? 'true' : undefined"
              :disabled="!canSwitch() || isCurrent(item.threadId)"
              :title="!canSwitch() ? '当前任务完成或停止后可以切换会话。' : item.title"
              @click="handleSelect(item.threadId)"
            >
              <span class="ga-history__row-main">
                <span class="ga-history__row-title">{{ item.title || '未命名对话' }}</span>
                <span class="ga-history__row-preview">{{ item.preview }}</span>
              </span>
              <span class="ga-history__row-meta">
                <time class="ga-history__time">{{ formatGaRelativeTime(item.updatedAt) }}</time>
                <span v-if="isCurrent(item.threadId)" class="ga-history__current" data-test="ga-history-current">
                  <AppIcon name="check" />
                  <span class="visually-hidden">当前会话</span>
                </span>
              </span>
            </button>
            <div class="ga-history__more">
              <button
                class="icon-btn ga-history__more-btn"
                type="button"
                data-test="ga-history-more"
                :data-thread="item.threadId"
                aria-label="更多操作"
                :aria-expanded="openMenuId === item.threadId ? 'true' : 'false'"
                aria-haspopup="menu"
                :disabled="isActiveThread(item.threadId) || deletingThreadId === item.threadId"
                :title="isActiveThread(item.threadId) ? '当前任务完成或停止后可以删除会话。' : '更多操作'"
                @click="toggleMenu(item.threadId)"
                @keydown="onMenuKeydown($event, item.threadId)"
              >
                <AppIcon name="more" />
              </button>
              <div v-if="openMenuId === item.threadId" class="ga-history__menu" role="menu" data-test="ga-history-menu">
                <button
                  class="ga-history__menu-item"
                  type="button"
                  role="menuitem"
                  data-test="ga-history-delete"
                  :disabled="isActiveThread(item.threadId)"
                  @click="emit('request-delete', item.threadId)"
                >删除对话</button>
              </div>
            </div>
            <div v-if="confirmDeleteThreadId === item.threadId" class="ga-history__confirm" role="alertdialog" aria-label="确认删除对话" data-test="ga-delete-confirm">
              <p class="ga-history__confirm-text">将删除这段对话记录。<br />已创建或修改的项目不会被删除。</p>
              <div class="ga-history__confirm-row">
                <button class="btn" type="button" data-test="ga-delete-cancel" @click="emit('cancel-delete')">取消</button>
                <button class="btn btn-danger" type="button" data-test="ga-delete-confirm-btn" :disabled="deletingThreadId === item.threadId" @click="emit('confirm-delete', item.threadId)">{{ deletingThreadId === item.threadId ? '删除中…' : '删除' }}</button>
              </div>
            </div>
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
 .ga-history {
  background: linear-gradient(180deg, rgba(255,255,255,0.9), var(--color-surface));
}
.ga-history__item { position: relative; }
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
.ga-history__row:hover:not(:disabled) { background: linear-gradient(135deg, var(--color-surface-subtle), rgba(255,255,255,0.8)); }
.ga-history__row[data-current='true'] { background: linear-gradient(135deg, var(--color-focus-soft), rgba(255,255,255,0.8)); border-left: 2px solid var(--color-focus); }
.ga-history__more { position: absolute; right: 6px; top: 8px; }
.ga-history__more-btn { opacity: 0; border-radius: 999px; background: rgba(255,255,255,0.9); border: 1px solid var(--color-border); width: 26px; height: 26px; display: inline-flex; align-items: center; justify-content: center; }
.ga-history__item:hover .ga-history__more-btn, .ga-history__more-btn:focus-visible, .ga-history__more-btn[aria-expanded='true'] { opacity: 1; }
.ga-history__more-btn:disabled { opacity: 0.35; }
.ga-history__menu { position: absolute; right: 0; top: 30px; min-width: 130px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; box-shadow: var(--shadow-float); padding: 4px; z-index: 5; }
.ga-history__menu-item { width: 100%; text-align: left; border: 0; background: transparent; padding: 8px 10px; border-radius: 8px; font-size: 13px; color: var(--color-text); }
.ga-history__menu-item:hover:not(:disabled) { background: var(--color-surface-subtle); }
.ga-history__menu-item:disabled { opacity: 0.5; cursor: not-allowed; }
.ga-history__confirm { margin: 6px 4px 10px; padding: 12px; border-radius: 14px; background: linear-gradient(135deg, #fff7ed, #fef2f2); border: 1px solid #fed7aa; box-shadow: 0 10px 28px -14px rgba(180,80,20,0.35); }
.ga-history__confirm-text { margin: 0 0 10px; font-size: 12.5px; line-height: 1.6; color: var(--color-text-secondary); }
.ga-history__confirm-row { display: flex; justify-content: flex-end; gap: 8px; }
.btn-danger { background: #fff; border: 1px solid #f0a8a0; color: #b42318; border-radius: 999px; padding: 6px 14px; }
.btn-danger:hover:not(:disabled) { background: #b42318; border-color: #b42318; color: #fff; }
@media (prefers-reduced-motion: reduce) {
  .ga-history__row {
    transition: none;
  }
}
</style>
