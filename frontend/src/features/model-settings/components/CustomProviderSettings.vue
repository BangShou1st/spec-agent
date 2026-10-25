<script setup lang="ts">
import { computed } from 'vue'
import { useOrganizationCapability } from '@/features/access/useOrganizationCapability'
import type { ProviderConnection } from '../api/modelProviders'
import { providerCardState, formatLabel } from '../presentation/providerPresentation'
import ProviderCard from './ProviderCard.vue'
import ProviderSummaryItem from './ProviderSummaryItem.vue'
const props = defineProps<{ connection: ProviderConnection; busy: boolean; error: { code: string } | null; active: boolean }>()
const emit = defineEmits<{ edit: []; validate: []; activate: []; delete: []; retry: [] }>()
const canManage = useOrganizationCapability('CREDENTIAL_MANAGE')
const state = computed(() => providerCardState(props.connection.configured, props.connection.validated,
  props.active, !!props.error, props.busy))
function capabilityLabel(status?: string) {
  return ({ VERIFIED: '已验证可用', UNSUPPORTED: '暂不支持', TEMPORARILY_UNAVAILABLE: '当前暂时不可用' } as Record<string,string>)[status || ''] || '尚未验证'
}
</script>
<template>
  <ProviderCard card-test-id="connection-panel" :title="connection.displayName || 'Custom'"
    title-test-id="custom-title"
    description="自定义 API 连接，按所选协议验证模型能力" :state="state" :error="error"
    state-test-id="custom-state" summary-test-id="custom-current"
    :retrying="busy" @retry="emit('retry')">
    <template #summary>
      <ProviderSummaryItem label="API Format" :value="formatLabel(connection.apiFormat)" test-id="custom-format" />
      <ProviderSummaryItem label="Base URL" :value="connection.baseUrl" test-id="custom-base-url" ellipsis />
      <ProviderSummaryItem label="Model" :value="connection.selectedModel" test-id="custom-current-model" ellipsis />
      <ProviderSummaryItem label="API Key" :value="connection.hasKey ? connection.maskedKey : '无鉴权'" test-id="custom-masked-key" />
      <ProviderSummaryItem label="普通聊天" :value="capabilityLabel(connection.verifiedCapabilities?.CHAT)" />
      <ProviderSummaryItem label="结构化输出" :value="capabilityLabel(connection.verifiedCapabilities?.STRUCTURED)" />
      <ProviderSummaryItem label="原生工具" :value="capabilityLabel(connection.verifiedCapabilities?.NATIVE_TOOLS)" />
    </template>
    <p v-if="!connection.validated" class="settings-field__hint" data-test="connection-status">配置已保存，请测试连接后再使用。</p>
    <p v-if="active" class="settings-field__hint">当前连接需先切换到其他模型后才能删除。</p>
    <template v-if="canManage" #footer>
      <button class="btn btn-danger settings-action" type="button" :disabled="busy || active" data-test="custom-delete" @click="emit('delete')">删除连接</button>
      <button class="btn settings-action" type="button" :disabled="busy" data-test="custom-edit" @click="emit('edit')">设置</button>
      <button class="btn settings-action" type="button" :disabled="busy" data-test="connection-validate" @click="emit('validate')">{{ busy ? '测试中…' : '重新测试' }}</button>
      <button class="btn btn-primary settings-action" type="button" :disabled="busy || !connection.validated || active"
        data-test="connection-activate" @click="emit('activate')">{{ active ? '当前使用' : '设为当前 Provider' }}</button>
    </template>
  </ProviderCard>
</template>
