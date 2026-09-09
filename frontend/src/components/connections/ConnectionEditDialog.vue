<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { ConnectionDetail } from '@/api/connectionTypes'
import type { ConnectionsStoreError } from '@/stores/connectionsStore'

const props = defineProps<{
  open: boolean
  detail: ConnectionDetail | null
  saving: boolean
  error: ConnectionsStoreError | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'save', payload: { name?: string; serverUrl?: string; secret?: string }): void
}>()

const name = ref('')
const serverUrl = ref('')
const secret = ref('')

watch(() => props.open, (v) => {
  if (v && props.detail) {
    name.value = props.detail.name
    const u = props.detail.config?.serverUrl
    serverUrl.value = typeof u === 'string' ? u : ''
    secret.value = ''
  }
})

function submit(): void {
  if (props.saving) return
  const patch: { name?: string; serverUrl?: string; secret?: string } = {}
  if (props.detail && name.value.trim() && name.value.trim() !== props.detail.name) patch.name = name.value.trim()
  const u = serverUrl.value.trim()
  const cur = typeof props.detail?.config?.serverUrl === 'string' ? (props.detail.config.serverUrl as string) : ''
  if (u && u !== cur) patch.serverUrl = u
  if (secret.value) patch.secret = secret.value
  if (Object.keys(patch).length === 0) { emit('close'); return }
  emit('save', patch)
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}
watch(() => props.open, (v) => {
  if (v) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="open" class="dlg-veil" data-test="connection-edit-dialog">
    <div class="dlg-card" role="dialog" aria-modal="true" aria-label="Edit connection">
      <header class="dlg-head"><h3>编辑连接</h3><button type="button" class="icon-btn" data-test="close-edit" aria-label="关闭" @click="emit('close')"><svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></button></header>
      <p class="muted">仅改名可保持当前状态；修改地址或凭证后需要重新测试、连接并启用。</p>
      <label class="settings-field" for="conn-edit-name"><span class="settings-field__label">名称</span>
        <input id="conn-edit-name" v-model="name" class="settings-control" type="text" autocomplete="off" maxlength="128" data-test="edit-name" /></label>
      <label class="settings-field" for="conn-edit-url"><span class="settings-field__label">服务器地址</span>
        <input id="conn-edit-url" v-model="serverUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" data-test="edit-server-url" /></label>
      <label class="settings-field" for="conn-edit-secret"><span class="settings-field__label">替换凭证（可选）</span>
        <span class="settings-field__hint">留空则保留原凭证，填写则替换。</span>
        <input id="conn-edit-secret" v-model="secret" class="settings-control" type="password" autocomplete="off" data-test="edit-secret" /></label>
      <div class="dlg-actions"><button type="button" class="btn btn-primary" data-test="submit-edit" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存' }}</button></div>
      <p v-if="error" class="error-banner" data-test="edit-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    </div>
  </div>
</template>

<style scoped>
.dlg-veil { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-start; justify-content: center; padding: 72px 16px 16px; background: rgb(23 28 40 / 42%); }
.dlg-card { width: 100%; max-width: 520px; display: flex; flex-direction: column; gap: 12px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 14px; padding: 20px 22px; box-shadow: var(--shadow-float); }
.dlg-head { display: flex; align-items: center; justify-content: space-between; }
.dlg-head h3 { margin: 0; font-size: 18px; }
.dlg-actions { display: flex; justify-content: flex-end; margin-top: 4px; }
.error-banner { margin: 4px 0 0; }
</style>
