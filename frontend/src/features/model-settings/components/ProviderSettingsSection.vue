<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { accessSession } from '@/features/access/accessSession'
import { useOrganizationCapability } from '@/features/access/useOrganizationCapability'
import { useProviderSettingsStore } from '../state/providerSettingsStore'
import { providerDisplayName } from '../presentation/providerPresentation'
import { listConnections, deleteConnection, validateConnection, activateConnection,
  type ProviderConnection } from '../api/modelProviders'
import { toDisplayError } from '@/shared/http/displayError'
import OpenCodeProviderSettings from './OpenCodeProviderSettings.vue'
import KiloProviderSettings from './KiloProviderSettings.vue'
import ProviderConnectionEditor from './ProviderConnectionEditor.vue'
import CustomProviderSettings from './CustomProviderSettings.vue'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import UiDialogShell from '@/shared/ui/UiDialogShell.vue'
const providers = useProviderSettingsStore()
const canManage = useOrganizationCapability('CREDENTIAL_MANAGE')
function scopeKey() {
  const identity = accessSession.identity
  return identity?.authenticated ? JSON.stringify([identity.userId, identity.orgId, identity.orgCapabilities.slice().sort()]) : null
}
const rows = ref<ProviderConnection[]>([])
const selected = ref('OPENCODE_ZEN')
const activeId = ref<string | null>(null)
const editing = ref<ProviderConnection | null>(null)
const dialogOpen = ref(false)
const busy = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const deleting = ref(false)
const customRows = computed(() => rows.value.filter(row => row.preset === 'CUSTOM'))
const current = computed(() => customRows.value.find(row => row.id === selected.value))
const activeLabel = computed(() => activeId.value
  ? rows.value.find(row => row.id === activeId.value)?.displayName || '连接不可用'
  : providerDisplayName(providers.activeProvider))
const presetTabs = [{ value: 'OPENCODE_ZEN', label: 'OpenCode Zen' }, { value: 'KILO', label: 'Kilo Gateway' }]
let loadGeneration = 0
async function load() {
  const identity = scopeKey()
  const generation = ++loadGeneration
  try {
    const result = await listConnections()
    if (scopeKey() !== identity || generation !== loadGeneration) return
    rows.value = result.providers; activeId.value = result.activeProviderId
    providers.activeProvider = result.activeProvider
  } catch (err) { if (scopeKey() === identity && generation === loadGeneration) error.value = toDisplayError(err) }
}
watch(scopeKey, () => {
  loadGeneration++
  rows.value = []; activeId.value = null; dialogOpen.value = false; editing.value = null
  selected.value = 'OPENCODE_ZEN'; error.value = null; busy.value = false
  if (accessSession.identity?.authenticated) void load()
}, { immediate: true, flush: 'sync' })
watch(() => providers.activeProvider, () => { void load() })
function openEditor(row: ProviderConnection | null) {
  if (!canManage.value) return
  editing.value = row; dialogOpen.value = true
}
function select(id: string) { selected.value = id; deleting.value = false; error.value = null }
async function saved(row: ProviderConnection) { dialogOpen.value = false; await load(); selected.value = row.id }
async function perform(action: 'validate' | 'activate' | 'delete') {
  const row = current.value
  if (!row || busy.value || !canManage.value) return
  const identity = scopeKey()
  busy.value = true; error.value = null
  try {
    if (action === 'validate') await validateConnection(row.id)
    if (action === 'activate') await activateConnection(row.id)
    if (action === 'delete') await deleteConnection(row.id)
    if (scopeKey() !== identity) return
    if (action === 'delete') select('OPENCODE_ZEN')
    await load()
  } catch (err) { if (scopeKey() === identity) error.value = toDisplayError(err) }
  finally { if (scopeKey() === identity) busy.value = false }
}
</script>
<template>
  <section class="provider-section" data-test="provider-section">
    <div class="provider-active-banner" data-test="active-provider-banner">
      <span class="provider-active-banner__label">当前使用</span><strong data-test="active-provider-name">{{ activeLabel }}</strong>
    </div>
    <ApiErrorBanner v-if="error && !current" :code="error.code" :message="error.message" retry-label="重新加载" @retry="load" />
    <div class="provider-tabs" role="tablist" aria-label="AI Provider" data-test="provider-tabs">
      <button v-for="tab in presetTabs" :key="tab.value" type="button" role="tab" class="provider-tab"
        :class="{ 'provider-tab--selected': selected === tab.value }" :aria-selected="selected === tab.value"
        :data-test="`provider-tab-${tab.value.toLowerCase()}`" @click="select(tab.value)">
        {{ tab.label }}<span v-if="!activeId && providers.activeProvider === tab.value" class="provider-tab__badge">当前</span>
      </button>
      <button v-for="row in customRows" :key="row.id" type="button" role="tab" class="provider-tab"
        :class="{ 'provider-tab--selected': selected === row.id }" :aria-selected="selected === row.id"
        :data-test="`provider-tab-${row.id}`" @click="select(row.id)">
        {{ row.displayName }}<span v-if="activeId === row.id" class="provider-tab__badge">当前</span>
      </button>
      <button v-if="canManage" type="button" class="provider-tab provider-tab--add" aria-label="添加自定义 Provider"
        data-test="provider-add-custom" @click="openEditor(null)">+</button>
    </div>
    <div v-if="selected === 'OPENCODE_ZEN'" role="tabpanel"><OpenCodeProviderSettings /></div>
    <div v-else-if="selected === 'KILO'" role="tabpanel"><KiloProviderSettings /></div>
    <div v-else-if="current" role="tabpanel">
      <CustomProviderSettings :connection="current" :busy="busy" :error="error" :active="activeId === current.id"
        @edit="openEditor(current)" @validate="perform('validate')" @activate="perform('activate')"
        @delete="deleting = true" @retry="perform('validate')" />
    </div>
    <UiDialogShell :open="deleting && !!current && canManage" title="删除自定义连接"
      description="删除后，绑定此连接的历史运行将无法继续调用模型。" test-id="connection-delete-dialog" @close="deleting = false">
      <p class="settings-field__hint">确认删除「{{ current?.displayName }}」？其他连接不受影响。</p>
      <template #actions>
        <button class="btn settings-action" type="button" :disabled="busy" @click="deleting = false">取消</button>
        <button class="btn btn-danger settings-action" type="button" :disabled="busy" @click="perform('delete')">确认删除</button>
      </template>
    </UiDialogShell>
    <UiDialogShell :open="dialogOpen && canManage" :title="editing ? '编辑自定义连接' : '添加自定义连接'"
      description="填写 API 地址和模型，保存后测试连接，再设为当前模型。" test-id="connection-dialog" @close="dialogOpen = false">
      <ProviderConnectionEditor v-if="dialogOpen" :connection="editing" @saved="saved" />
    </UiDialogShell>
  </section>
</template>
<style scoped>
.provider-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.provider-active-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 10px;
  font-size: 13px;
}
.provider-active-banner__label {
  color: var(--color-text-secondary);
}
.provider-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.provider-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-weight: 600;
  font-size: 13px;
}
.provider-tab--selected {
  color: var(--color-accent-strong);
  background: var(--color-accent-soft);
  border-color: var(--color-accent);
}
.provider-tab:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.provider-tab__badge {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-success-soft);
  color: var(--color-success);
  font-size: 11px;
  font-weight: 700;
}
.provider-tab--add {
  padding: 8px 12px;
  color: var(--color-text-muted);
}
.provider-tab { max-width: 100%; overflow-wrap: anywhere; }
</style>
