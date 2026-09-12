<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { managementErrorMessage } from '@/api/errorCopy'
import BackLink from '@/components/BackLink.vue'
import ConnectionLifecycleAction from '@/components/connections/ConnectionLifecycleAction.vue'
import ConnectionCapabilityBrowser from '@/components/connections/ConnectionCapabilityBrowser.vue'
import ConnectionEditDialog from '@/components/connections/ConnectionEditDialog.vue'
import { connectionKindLabel } from '@/presentation/managementCopy'
import { useConnectionsStore } from '@/stores/connectionsStore'

const props = defineProps<{ connectionId: string }>()
const store = useConnectionsStore()
const router = useRouter()

const editOpen = ref(false)
const confirmingDelete = ref(false)

const endpoint = computed(() => {
  const u = store.detail?.config?.serverUrl
  return typeof u === 'string' && u ? u : '—'
})

function load(): void {
  confirmingDelete.value = false
  void store.loadDetail(props.connectionId)
  void store.loadResources(props.connectionId)
  void store.loadPrompts(props.connectionId)
}

onMounted(load)
watch(() => props.connectionId, load)

async function saveEdit(patch: { name?: string; serverUrl?: string; secret?: string }): Promise<void> {
  const body: Record<string, unknown> = {}
  if (patch.name !== undefined) body.name = patch.name
  if (patch.serverUrl !== undefined) body.config = { serverUrl: patch.serverUrl }
  if (patch.secret !== undefined) body.secret = patch.secret
  const ok = await store.update(props.connectionId, body)
  if (ok) {
    editOpen.value = false
    void store.loadResources(props.connectionId)
    void store.loadPrompts(props.connectionId)
  }
}

async function remove(): Promise<void> {
  const ok = await store.remove(props.connectionId)
  if (ok) void router.push('/settings/connections')
}
</script>

<template>
  <section class="mgmt-page" data-test="connection-detail-page">
    <BackLink to="/settings/connections" label="返回 Connections" test-id="back-to-connections" />
    <p v-if="store.detailLoading" class="muted" data-test="connection-detail-loading">加载中…</p>
    <p v-else-if="store.error && !store.detail" class="error-banner" data-test="connection-detail-error">
      <span>{{ managementErrorMessage(store.error.code, store.error.message) }}</span>
      <button type="button" class="btn" data-test="connection-detail-retry" @click="load">重试</button>
    </p>
    <template v-else-if="store.detail">
      <header class="detail-head">
        <div><h2 data-test="connection-detail-name">{{ store.detail.name }}</h2>
        <p class="muted">{{ connectionKindLabel(store.detail.kind) }} · {{ endpoint }}</p></div>
      <button type="button" class="btn" data-test="edit-connection" @click="store.clearError(); editOpen = true">编辑连接</button>
      </header>
      <dl class="detail-meta">
        <div><dt>服务器地址</dt><dd data-test="connection-endpoint">{{ endpoint }}</dd></div>
        <div><dt>凭证</dt><dd data-test="connection-credential">{{ store.detail.hasCredential ? (store.detail.maskedSuffix ?? '已设置') : '未设置' }}</dd></div>
      </dl>
      <ConnectionLifecycleAction
        :detail="store.detail"
        :working="store.actionLoading"
        :error="store.error"
        @test="store.test(props.connectionId)"
        @connect="store.connect(props.connectionId)"
        @refresh="store.refresh(props.connectionId)"
        @enable="store.enable(props.connectionId)"
      />
      <ConnectionCapabilityBrowser
        :tools="store.tools"
        :resources="store.resources"
        :prompts="store.prompts"
        :resource-content="store.resourceContent"
        @read-resource="(uri) => store.readResource(props.connectionId, uri)"
        @close-resource="store.resourceContent = null"
      />
      <section class="danger-zone" data-test="danger-zone">
        <h3>危险操作</h3>
        <p class="muted">禁用随时可恢复。删除会移除连接及其发现缓存，且不可撤销。</p>
        <div class="danger-row">
          <button v-if="store.detail.enabled" type="button" class="btn" data-test="connection-disable" :disabled="store.actionLoading" @click="store.disable(props.connectionId)">禁用</button>
          <button v-if="!confirmingDelete" type="button" class="btn" data-test="connection-delete" :disabled="store.actionLoading" @click="confirmingDelete = true">删除</button>
          <span v-else class="delete-confirm">
            <span>删除该连接及其发现缓存？此操作不可撤销。</span>
            <button type="button" class="btn btn-danger" data-test="connection-delete-confirm" :disabled="store.actionLoading" @click="remove">确认删除</button>
            <button type="button" class="btn" data-test="connection-delete-cancel" @click="confirmingDelete = false">取消</button>
          </span>
        </div>
      </section>
      <ConnectionEditDialog :open="editOpen" :detail="store.detail" :saving="store.actionLoading" :error="store.error" @close="editOpen = false" @save="saveEdit" />
    </template>
  </section>
</template>

<style scoped>
.mgmt-page { width: 100%; max-width: 880px; margin: 0 auto; padding: 8px 0 48px; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.detail-head h2 { margin: 0; font-size: 24px; }
.detail-head .muted { margin: 4px 0 0; word-break: break-all; }
.detail-meta { display: grid; gap: 8px; margin: 14px 0; }
.detail-meta div { display: grid; grid-template-columns: 72px 1fr; gap: 8px; }
.detail-meta dt { color: var(--color-text-muted); font-size: 12px; }
.detail-meta dd { margin: 0; font-size: 13px; word-break: break-all; }
.error-banner { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.danger-zone { margin-top: 28px; padding-top: 18px; border-top: 1px solid var(--color-border); }
.danger-zone h3 { margin: 0 0 4px; font-size: 14px; }
.danger-zone .muted { margin: 0 0 12px; font-size: 13px; }
.danger-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.delete-confirm { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--color-danger); }
</style>
