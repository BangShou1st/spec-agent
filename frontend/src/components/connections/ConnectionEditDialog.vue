<script setup lang="ts">
import { ref, watch } from 'vue'
import UiDialogShell from '@/components/ui/UiDialogShell.vue'
import UiFormField from '@/components/ui/UiFormField.vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { ConnectionDetail } from '@/api/connectionTypes'
import type { ConnectionsStoreError } from '@/stores/connectionsStore'
const props = defineProps<{ open: boolean; detail: ConnectionDetail | null; saving: boolean; error: ConnectionsStoreError | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'save', payload: { name?: string; serverUrl?: string; secret?: string }): void }>()
const name = ref('')
const serverUrl = ref('')
const secret = ref('')
watch(() => props.open, (v) => { if (v && props.detail) { name.value = props.detail.name; const u = (props.detail as unknown as { config?: { serverUrl?: unknown } }).config?.serverUrl; serverUrl.value = typeof u === 'string' ? u : ''; secret.value = '' } })
function submit(): void { if (props.saving) return; const patch: { name?: string; serverUrl?: string; secret?: string } = {}; if (props.detail && name.value.trim() && name.value.trim() !== props.detail.name) patch.name = name.value.trim(); const u = serverUrl.value.trim(); const cur = typeof (props.detail as unknown as { config?: { serverUrl?: unknown } })?.config?.serverUrl === 'string' ? ((props.detail as unknown as { config: { serverUrl: string } }).config.serverUrl) : ''; if (u && u !== cur) patch.serverUrl = u; if (secret.value) patch.secret = secret.value; if (Object.keys(patch).length === 0) { emit('close'); return } emit('save', patch) }
</script>
<template>
  <UiDialogShell :open="open" title="编辑连接" description="仅改名可保持当前状态；修改地址或凭证后需要重新测试、连接并启用。" test-id="connection-edit-dialog" @close="emit('close')">
    <UiFormField label="名称" html-for="conn-edit-name">
      <input id="conn-edit-name" v-model="name" class="settings-control" type="text" autocomplete="off" maxlength="128" data-test="edit-name" />
    </UiFormField>
    <UiFormField label="服务器地址" html-for="conn-edit-url">
      <input id="conn-edit-url" v-model="serverUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" data-test="edit-server-url" />
    </UiFormField>
    <UiFormField label="替换凭证（可选）" html-for="conn-edit-secret" hint="留空则保留原凭证，填写则替换。">
      <input id="conn-edit-secret" v-model="secret" class="settings-control" type="password" autocomplete="off" data-test="edit-secret" />
    </UiFormField>
    <p v-if="error" class="error-banner" data-test="edit-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    <template #actions>
      <button type="button" class="btn btn-secondary" data-test="close-edit" @click="emit('close')">取消</button>
      <button type="button" class="btn btn-primary" data-test="submit-edit" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存' }}</button>
    </template>
  </UiDialogShell>
</template>
<style scoped>
.error-banner { margin: 0; }
</style>
