<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { accessSession } from '@/features/access/accessSession'
import { useOrganizationCapability } from '@/features/access/useOrganizationCapability'
import { sameOrigin } from '@/shared/net/origin'
import { toDisplayError } from '@/shared/http/displayError'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import ProviderModelField from './ProviderModelField.vue'
import { CUSTOM_FORMAT_OPTIONS } from '../presentation/providerPresentation'
import { discoverConnection, saveConnection, type ProviderConnection, type CustomApiFormat } from '../api/modelProviders'

const props = defineProps<{ connection: ProviderConnection | null }>()
const emit = defineEmits<{ saved: [connection: ProviderConnection] }>()
const canManage = useOrganizationCapability('CREDENTIAL_MANAGE')
function scopeKey() {
  const identity = accessSession.identity
  return identity?.authenticated ? JSON.stringify([identity.userId, identity.orgId, identity.orgCapabilities.slice().sort()]) : null
}
const name = ref(props.connection?.displayName || '')
const baseUrl = ref(props.connection?.baseUrl || '')
const format = ref<CustomApiFormat>(props.connection?.apiFormat || 'CHAT_COMPLETIONS')
const model = ref(props.connection?.selectedModel || '')
const key = ref('')
const keyTouched = ref(false)
const models = ref<string[]>([])
const freeModels = ref<string[]>([])
const onlyFree = ref(false)
const busy = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const visibleModels = computed(() => onlyFree.value ? freeModels.value : models.value)
const changedOrigin = computed(() => props.connection?.hasKey && !keyTouched.value
  && !sameOrigin(baseUrl.value, props.connection.baseUrl))
const canSave = computed(() => canManage.value && name.value.trim() && baseUrl.value.trim()
  && model.value.trim() && !busy.value && !changedOrigin.value)
watch(() => [baseUrl.value, format.value], () => { models.value = []; freeModels.value = []; onlyFree.value = false })
watch(canManage, allowed => { if (!allowed) key.value = '' }, { flush: 'sync' })

async function perform(work: () => Promise<void>) {
  if (busy.value || !canManage.value || changedOrigin.value) return
  const identity = scopeKey()
  busy.value = true
  error.value = null
  try { await work() } catch (err) {
    if (scopeKey() === identity) error.value = toDisplayError(err)
  } finally { busy.value = false }
}
function discover() {
  return perform(async () => {
    const identity = scopeKey()
    const result = await discoverConnection(props.connection?.id || null, {
      apiFormat: format.value, baseUrl: baseUrl.value.trim(),
      apiKey: keyTouched.value ? key.value.trim() : undefined,
    })
    if (scopeKey() !== identity) return
    models.value = result.models
    freeModels.value = result.freeModels
  })
}
function save() {
  if (!canSave.value) return
  return perform(async () => {
    const identity = scopeKey()
    const result = await saveConnection(props.connection?.id || null, {
      displayName: name.value.trim(), apiFormat: format.value, baseUrl: baseUrl.value.trim(),
      apiKey: keyTouched.value ? key.value.trim() : undefined,
      selectedModel: model.value.trim(), modelSource: 'MANUAL',
    })
    key.value = ''
    if (scopeKey() === identity) emit('saved', result)
  })
}
</script>

<template>
  <form class="settings-form" @submit.prevent="save">
    <ApiErrorBanner v-if="error" class="settings-error" :code="error.code" :message="error.message"
      retry-label="关闭提示" @retry="error = null" />
    <label class="settings-field">
      <span class="settings-field__label">显示名称</span>
      <span class="settings-field__hint">显示在连接标签与「当前使用」中</span>
      <input v-model="name" class="settings-control" maxlength="64" required :disabled="busy"
        autocomplete="off" placeholder="例如 自建模型服务" data-test="connection-name" />
    </label>
    <label class="settings-field">
      <span class="settings-field__label">API Format</span>
      <span class="settings-field__hint">选择服务商提供的协议格式</span>
      <select v-model="format" class="settings-control settings-model-select" :disabled="busy" data-test="connection-format">
        <option v-for="option in CUSTOM_FORMAT_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
      </select>
    </label>
    <label class="settings-field">
      <span class="settings-field__label">Base URL</span>
      <span class="settings-field__hint">API 根地址，通常以 /v1 结尾</span>
      <input v-model="baseUrl" class="settings-control" type="url" required :disabled="busy"
        placeholder="https://api.example.com/v1" data-test="connection-url" />
    </label>
    <label class="settings-field">
      <span class="settings-field__label">API Key</span>
      <span class="settings-field__hint">密钥只保存一次，不会再次完整显示。</span>
      <input v-model="key" class="settings-control" type="password" autocomplete="new-password" :disabled="busy"
        :placeholder="connection?.hasKey ? '留空保留已存密钥' : '无鉴权服务可留空'"
        data-test="connection-key" @input="keyTouched = true" />
    </label>
    <div class="settings-form__action-row">
      <button v-if="connection?.hasKey" type="button" class="btn settings-action" :disabled="busy"
        @click="key = ''; keyTouched = true">清除已存密钥</button>
      <button type="button" class="btn settings-action" :disabled="busy || !baseUrl.trim() || changedOrigin"
        data-test="connection-discover" @click="discover">{{ busy ? '处理中…' : '获取模型' }}</button>
    </div>
    <p v-if="changedOrigin" class="settings-field__hint settings-field__warning" data-test="connection-origin-warning" role="alert">
      地址已更换服务商，请输入新密钥或明确清除旧密钥。
    </p>
    <ProviderModelField :model-value="model" :models="visibleModels" :disabled="busy" :allow-manual="true"
      :free-only="freeModels.length ? onlyFree : undefined" free-toggle-id="connection-free-only"
      test-id="connection-model" hint="选择或手动填写模型 ID；仅免费筛选依据目录标价，未知价格不会按免费处理。"
      empty-text="也可直接输入模型 ID，然后测试连接。" placeholder="选择或输入模型 ID"
      @update:model-value="model = $event || ''" @update:free-only="onlyFree = $event" />
    <footer class="settings-dialog__footer">
      <button type="submit" class="btn btn-primary settings-action" :disabled="!canSave" data-test="connection-save">
        {{ busy ? '保存中…' : '保存连接' }}
      </button>
    </footer>
  </form>
</template>
