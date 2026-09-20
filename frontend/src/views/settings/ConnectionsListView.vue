<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import { useRouter } from 'vue-router'
import { managementErrorMessage } from '@/api/errorCopy'
import ConnectionsList from '@/components/connections/ConnectionsList.vue'
import ConnectionCreateDialog from '@/components/connections/ConnectionCreateDialog.vue'
import { useConnectionsStore } from '@/stores/connectionsStore'

const store = useConnectionsStore()
const router = useRouter()
const createOpen = ref(false)
const busyId = ref<string | null>(null)

onMounted(() => { void store.loadList() })

function select(id: string): void {
  void router.push(`/settings/connections/${encodeURIComponent(id)}`)
}

async function create(payload: { name: string; serverUrl: string; secret?: string }): Promise<void> {
  const created = await store.create({
    kind: 'CUSTOM_MCP',
    name: payload.name,
    config: { serverUrl: payload.serverUrl },
    ...(payload.secret ? { secret: payload.secret } : {}),
  })
  if (created) {
    createOpen.value = false
    void router.push(`/settings/connections/${encodeURIComponent(created.connectionId)}`)
  }
}

async function enable(id: string): Promise<void> {
  busyId.value = id
  await store.enable(id)
  busyId.value = null
}

async function disable(id: string): Promise<void> {
  busyId.value = id
  await store.disable(id)
  busyId.value = null
}

async function remove(id: string): Promise<void> {
  busyId.value = id
  await store.remove(id)
  busyId.value = null
}

function retry(): void {
  void store.loadList()
}
</script>

<template>
  <section class="mgmt-page" data-test="connections-page">
    <header class="mgmt-head">
      <div><h2>Connections</h2><p class="muted">连接外部 MCP 服务，让 Agent 在任务中使用它们的工具与资源</p></div>
      <button type="button" class="btn btn-primary" data-test="add-connection" @click="store.clearError(); createOpen = true">+ 新建连接</button>
    </header>
    <ApiErrorBanner
      v-if="store.error && !store.list.length && !store.listLoading"
      :message="managementErrorMessage(store.error.code, store.error.message)"
      retry-label="重试"
      data-test="connections-error"
      @retry="retry"
    />
    <div v-if="store.listLoading" class="muted" data-test="connections-loading">加载中…</div>
    <p v-else-if="!store.list.length && !store.error" class="mgmt-empty" data-test="connections-empty">还没有连接，点击右上角新建第一个连接</p>
    <ConnectionsList v-else :connections="store.list" :loading="store.listLoading" :busy-id="busyId" @select="select" @enable="enable" @disable="disable" @remove="remove" />
    <p v-if="store.error && store.list.length" class="mgmt-inline-error" data-test="connections-action-error">{{ managementErrorMessage(store.error.code, store.error.message) }}</p>
    <ConnectionCreateDialog :open="createOpen" :saving="store.actionLoading" :error="store.error" @close="createOpen = false" @create="create" />
  </section>
</template>

<style scoped>
</style>
