<script setup lang="ts">
import { ref, watch } from 'vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { ConnectionsStoreError } from '@/stores/connectionsStore'

const props = defineProps<{
  open: boolean
  saving: boolean
  error: ConnectionsStoreError | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'create', payload: { name: string; serverUrl: string; secret?: string }): void
}>()

const name = ref('')
const serverUrl = ref('')
const secret = ref('')
const showSecret = ref(false)

watch(() => props.open, (v) => {
  if (v) {
    name.value = ''
    serverUrl.value = ''
    secret.value = ''
    showSecret.value = false
  }
})

const valid = () => name.value.trim().length > 0 && serverUrl.value.trim().length > 0

function submit(): void {
  if (!valid() || props.saving) return
  const s = secret.value
  emit('create', { name: name.value.trim(), serverUrl: serverUrl.value.trim(), secret: s ? s : undefined })
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}
</script>

<template>
  <div v-if="open" class="dlg-veil" data-test="connection-create-dialog" @keydown="onKey">
    <div class="dlg-card" role="dialog" aria-modal="true" aria-label="Create connection">
      <header class="dlg-head"><h3>新建连接</h3><button type="button" class="btn" data-test="close-create" @click="emit('close')">关闭</button></header>
      <p class="muted">Custom MCP 连接。凭证只保存一次，不会再次完整显示。</p>
      <label class="settings-field" for="conn-name"><span class="settings-field__label">名称</span>
        <input id="conn-name" v-model="name" class="settings-control" type="text" autocomplete="off" maxlength="128" placeholder="例如 Research Tools" data-test="conn-name" /></label>
      <label class="settings-field" for="conn-url"><span class="settings-field__label">服务器地址</span>
        <input id="conn-url" v-model="serverUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" placeholder="https://example.com/mcp" data-test="conn-server-url" /></label>
      <label class="settings-field" for="conn-secret"><span class="settings-field__label">凭证（可选）</span>
        <span class="settings-field__hint">如服务器需要认证则填写，保存后无法查看原文。</span>
        <span class="secret-row"><input id="conn-secret" v-model="secret" class="settings-control" :type="showSecret ? 'text' : 'password'" autocomplete="off" data-test="conn-secret" />
        <button type="button" class="btn" data-test="toggle-secret" @click="showSecret = !showSecret">{{ showSecret ? '隐藏' : '显示' }}</button></span></label>
      <div class="dlg-actions"><button type="button" class="btn btn-primary" data-test="submit-create" :disabled="!valid() || saving" @click="submit">{{ saving ? '创建中…' : '创建并查看' }}</button></div>
      <p v-if="error" class="error-banner" data-test="create-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    </div>
  </div>
</template>

<style scoped>
.dlg-veil { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-start; justify-content: center; padding: 72px 16px 16px; background: rgb(23 28 40 / 42%); }
.dlg-card { width: 100%; max-width: 520px; display: flex; flex-direction: column; gap: 12px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 14px; padding: 20px 22px; box-shadow: var(--shadow-float); }
.dlg-head { display: flex; align-items: center; justify-content: space-between; }
.dlg-head h3 { margin: 0; font-size: 18px; }
.secret-row { display: flex; gap: 8px; }
.secret-row .settings-control { flex: 1; }
.dlg-actions { display: flex; justify-content: flex-end; margin-top: 4px; }
.error-banner { margin: 4px 0 0; }
</style>
