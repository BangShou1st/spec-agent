<script setup lang="ts">
import { computed } from 'vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { ConnectionDetail } from '@/api/connectionTypes'
import type { ConnectionsStoreError } from '@/stores/connectionsStore'

const props = defineProps<{
  detail: ConnectionDetail
  working: boolean
  error: ConnectionsStoreError | null
}>()

const emit = defineEmits<{
  (e: 'test'): void
  (e: 'connect'): void
  (e: 'refresh'): void
  (e: 'enable'): void
}>()

type Phase = 'created' | 'tested' | 'connected' | 'enabled' | 'failed'

const phase = computed<Phase>(() => {
  if (props.detail.enabled) return 'enabled'
  if (props.detail.status === 'FAILED') return 'failed'
  if (props.detail.status === 'CONNECTED') return 'connected'
  if (props.detail.status === 'TESTED') return 'tested'
  return 'created'
})

const hint = computed(() => {
  switch (phase.value) {
    case 'created': return '尚未测试，先验证这个连接是否可用。'
    case 'tested': return '测试通过，现在可以建立连接。'
    case 'connected': return '连接成功，启用后 Agent 才可以使用。'
    case 'enabled': return 'Agent 已经可以使用这个连接。'
    case 'failed': return props.detail.lastError ? props.detail.lastError : '连接失败，请重新测试。'
  }
})
</script>

<template>
  <section class="lifecycle" data-test="lifecycle-action">
    <p class="lifecycle-hint" data-test="lifecycle-hint">{{ hint }}</p>
    <div class="lifecycle-row">
      <button v-if="phase === 'created' || phase === 'failed'" type="button" class="btn btn-primary" data-test="lifecycle-test" :disabled="working" @click="emit('test')">{{ working ? '测试中…' : '测试连接' }}</button>
      <button v-else-if="phase === 'tested'" type="button" class="btn btn-primary" data-test="lifecycle-connect" :disabled="working" @click="emit('connect')">{{ working ? '连接中…' : '连接' }}</button>
      <button v-else-if="phase === 'connected'" type="button" class="btn btn-primary" data-test="lifecycle-enable" :disabled="working" @click="emit('enable')">{{ working ? '启用中…' : '启用' }}</button>
      <span v-else class="enabled-line" data-test="lifecycle-enabled">
        <span class="st st--on"><span class="st__dot" aria-hidden="true"></span>已启用</span>
        <button type="button" class="btn" data-test="lifecycle-refresh" :disabled="working" @click="emit('refresh')">{{ working ? '刷新中…' : '刷新' }}</button>
      </span>
    </div>
    <p v-if="error" class="lifecycle-error" data-test="lifecycle-error">{{ managementErrorMessage(error.code, error.message) }}</p>
  </section>
</template>

<style scoped>
.lifecycle { margin: 14px 0; padding: 12px 14px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 10px; }
.lifecycle-hint { margin: 0 0 10px; color: var(--color-text-secondary); font-size: 13px; }
.lifecycle-row { display: flex; }
.enabled-line { display: inline-flex; align-items: center; gap: 12px; }
.st { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 700; }
.st--on { color: var(--color-success); }
.st__dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.lifecycle-error { margin: 10px 0 0; color: var(--color-danger); font-size: 13px; }
</style>
