<script setup lang="ts">
import { ref } from 'vue'
import { connectionKindLabel } from '@/presentation/managementCopy'
import type { ConnectionSummary } from '@/api/connectionTypes'

withDefaults(defineProps<{
  connections: ConnectionSummary[]
  loading: boolean
  busyId?: string | null
}>(), { busyId: null })

const emit = defineEmits<{
  (e: 'select', connectionId: string): void
  (e: 'enable', connectionId: string): void
  (e: 'disable', connectionId: string): void
  (e: 'remove', connectionId: string): void
}>()

const confirmingId = ref<string | null>(null)

function endpointOf(c: ConnectionSummary): string {
  const u = c.config?.serverUrl
  return typeof u === 'string' && u ? u : connectionKindLabel(c.kind)
}

function statusText(c: ConnectionSummary): string {
  if (c.enabled) return '已启用'
  if (c.status === 'FAILED') return '连接失败'
  if (c.status === 'CREATED') return '未测试'
  return '已禁用'
}

function statusClass(c: ConnectionSummary): string {
  if (c.enabled) return 'st--on'
  if (c.status === 'FAILED') return 'st--fail'
  return 'st--off'
}
</script>

<template>
  <div class="conns-list" data-test="connections-list">
    <p v-if="loading" class="muted" data-test="connections-loading">Loading connections...</p>
    <ul v-else class="conns-rows">
      <li v-for="c in connections" :key="c.connectionId" class="conns-row" :data-test="`connection-row-${c.connectionId}`">
        <button type="button" class="conns-main" :data-test="`connection-select-${c.connectionId}`" @click="emit('select', c.connectionId)">
          <span class="conns-name">{{ c.name }}</span>
          <span class="conns-endpoint">{{ endpointOf(c) }}</span>
        </button>
        <span class="st" :class="statusClass(c)" :data-test="`connection-status-${c.connectionId}`">
          <span class="st__dot" aria-hidden="true"></span>{{ statusText(c) }}</span>
        <details class="conns-more" :data-test="`connection-more-${c.connectionId}`">
          <summary aria-label="More actions">...</summary>
          <div class="conns-more__actions">
            <button v-if="!c.enabled" type="button" class="btn" :disabled="busyId === c.connectionId" :data-test="`connection-enable-${c.connectionId}`" @click="emit('enable', c.connectionId)">启用</button>
            <button v-else type="button" class="btn" :disabled="busyId === c.connectionId" :data-test="`connection-disable-${c.connectionId}`" @click="emit('disable', c.connectionId)">禁用</button>
            <button v-if="confirmingId !== c.connectionId" type="button" class="btn" :disabled="busyId === c.connectionId" :data-test="`connection-delete-${c.connectionId}`" @click="confirmingId = c.connectionId">删除</button>
            <span v-else class="conns-confirm">
              <span>删除该连接及其发现缓存？</span>
              <button type="button" class="btn btn-danger" :disabled="busyId === c.connectionId" :data-test="`connection-delete-confirm-${c.connectionId}`" @click="emit('remove', c.connectionId); confirmingId = null">确认删除</button>
              <button type="button" class="btn" :data-test="`connection-delete-cancel-${c.connectionId}`" @click="confirmingId = null">取消</button>
            </span>
          </div>
        </details>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.conns-list { width: 100%; }
.conns-rows { list-style: none; margin: 0; padding: 0; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.conns-row { display: flex; align-items: center; gap: 12px; padding: 13px 16px; transition: background 120ms ease; }
.conns-row:hover { background: var(--color-surface-subtle); }
.conns-row + .conns-row { border-top: 1px solid var(--color-border); }
.conns-main { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; background: none; border: none; padding: 0; text-align: left; cursor: pointer; }
.conns-main:focus-visible { outline: none; box-shadow: var(--focus-ring); border-radius: 6px; }
.conns-name { font-weight: 600; }
.conns-endpoint { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-secondary); font-size: 13px; }
.st { flex: none; display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.st--on { color: var(--color-success); }
.st--fail { color: var(--color-danger); }
.st--off { color: var(--color-text-secondary); }
.st__dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.conns-more { flex: none; position: relative; }
.conns-more summary { cursor: pointer; list-style: none; padding: 4px 8px; border-radius: 6px; color: var(--color-text-secondary); font-weight: 700; letter-spacing: 0.1em; }
.conns-more summary:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.conns-more__actions { position: absolute; right: 0; top: calc(100% + 4px); z-index: 5; display: flex; flex-direction: column; gap: 8px; min-width: 220px; padding: 12px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 10px; box-shadow: var(--shadow-card); }
.conns-confirm { display: flex; flex-direction: column; gap: 8px; font-size: 13px; color: var(--color-text-secondary); }
</style>
