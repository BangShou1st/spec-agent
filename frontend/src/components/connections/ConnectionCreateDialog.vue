<script setup lang="ts">
import { ref, watch } from 'vue'
import UiDialogShell from '@/components/ui/UiDialogShell.vue'
import UiFormField from '@/components/ui/UiFormField.vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { ConnectionsStoreError } from '@/stores/connectionsStore'
const props = defineProps<{ open: boolean; saving: boolean; error: ConnectionsStoreError | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'create', payload: { name: string; serverUrl: string; secret?: string }): void }>()
const name = ref('')
const serverUrl = ref('')
const secret = ref('')
const showSecret = ref(false)
watch(() => props.open, (v) => { if (v) { name.value = ''; serverUrl.value = ''; secret.value = ''; showSecret.value = false } })
const valid = () => name.value.trim().length > 0 && serverUrl.value.trim().length > 0
function submit(): void { if (!valid() || props.saving) return; const s = secret.value; emit('create', { name: name.value.trim(), serverUrl: serverUrl.value.trim(), secret: s ? s : undefined }) }
</script>
<template>
  <UiDialogShell :open="open" title="新建连接" description="Custom MCP 连接。凭证只保存一次，不会再次完整显示。" test-id="connection-create-dialog" @close="emit('close')">
    <UiFormField label="名称" html-for="conn-name">
      <input id="conn-name" v-model="name" class="settings-control" type="text" autocomplete="off" maxlength="128" placeholder="例如 Research Tools" data-test="conn-name" />
    </UiFormField>
    <UiFormField label="服务器地址" html-for="conn-url">
      <input id="conn-url" v-model="serverUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" placeholder="https://example.com/mcp" data-test="conn-server-url" />
    </UiFormField>
    <UiFormField label="凭证（可选）" html-for="conn-secret" hint="如服务器需要认证则填写，保存后无法查看原文。">
      <span class="secret-row">
        <input id="conn-secret" v-model="secret" class="settings-control" :type="showSecret ? 'text' : 'password'" autocomplete="off" data-test="conn-secret" />
        <button type="button" class="btn btn-secondary" data-test="toggle-secret" @click="showSecret = !showSecret">{{ showSecret ? '隐藏' : '显示' }}</button>
      </span>
    </UiFormField>
    <p v-if="error" class="error-banner" data-test="create-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    <template #actions>
      <button type="button" class="btn btn-secondary" data-test="close-create" @click="emit('close')">取消</button>
      <button type="button" class="btn btn-primary" data-test="submit-create" :disabled="!valid() || saving" @click="submit">{{ saving ? '创建中…' : '创建并查看' }}</button>
    </template>
  </UiDialogShell>
</template>
<style scoped>
.error-banner { margin: 0; }
</style>
