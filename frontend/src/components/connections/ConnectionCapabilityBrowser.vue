<script setup lang="ts">
import { ref } from 'vue'
import type { ConnectionPromptView, ConnectionResourceContent, ConnectionResourceView, ConnectionToolView } from '@/api/connectionTypes'

withDefaults(defineProps<{
  tools: ConnectionToolView[]
  resources: ConnectionResourceView[]
  prompts: ConnectionPromptView[]
  resourceContent?: ConnectionResourceContent | null
  loading?: boolean
}>(), { resourceContent: null, loading: false })

const emit = defineEmits<{
  (e: 'read-resource', uri: string): void
  (e: 'close-resource'): void
}>()

const tab = ref<'tools' | 'resources' | 'prompts'>('tools')
const openTool = ref<string | null>(null)

function toggleTool(name: string): void {
  openTool.value = openTool.value === name ? null : name
}
</script>

<template>
  <section class="cap" data-test="capability-browser">
    <div class="cap-tabs" role="tablist" aria-label="Connection capabilities">
      <button type="button" role="tab" :aria-selected="tab === 'tools'" :class="{ active: tab === 'tools' }" data-test="cap-tab-tools" @click="tab = 'tools'">Tools ({{ tools.length }})</button>
      <button type="button" role="tab" :aria-selected="tab === 'resources'" :class="{ active: tab === 'resources' }" data-test="cap-tab-resources" @click="tab = 'resources'">Resources ({{ resources.length }})</button>
      <button type="button" role="tab" :aria-selected="tab === 'prompts'" :class="{ active: tab === 'prompts' }" data-test="cap-tab-prompts" @click="tab = 'prompts'">Prompts ({{ prompts.length }})</button>
    </div>
    <div v-if="tab === 'tools'" data-test="cap-tools">
      <p v-if="!tools.length" class="muted">暂无工具。先完成测试与连接，工具会出现在这里。</p>
      <ul v-else class="cap-list">
        <li v-for="t in tools" :key="t.name" :data-test="`cap-tool-${t.name}`">
          <button type="button" class="cap-row" :data-test="`cap-tool-toggle-${t.name}`" @click="toggleTool(t.name)">
            <span class="cap-name">{{ t.name }}</span>
            <span class="muted cap-desc">{{ t.description }}</span>
          </button>
          <div v-if="openTool === t.name" class="cap-detail">
            <h5>Input schema</h5><pre>{{ JSON.stringify(t.inputSchema, null, 2) }}</pre>
            <h5>Annotations</h5><pre>{{ JSON.stringify(t.annotations, null, 2) }}</pre>
          </div>
        </li>
      </ul>
    </div>
    <div v-else-if="tab === 'resources'" data-test="cap-resources">
      <p v-if="!resources.length" class="muted">暂无资源。</p>
      <ul v-else class="cap-list">
        <li v-for="r in resources" :key="r.uri" :data-test="`cap-resource-${r.uri}`">
          <button type="button" class="cap-row" :data-test="`cap-resource-read-${r.uri}`" @click="emit('read-resource', r.uri)">
            <span class="cap-name">{{ r.name || r.uri }}</span>
            <span class="muted cap-desc">{{ r.uri }} · {{ r.mimeType }}</span>
          </button>
        </li>
      </ul>
      <div v-if="resourceContent" class="res-read" data-test="cap-resource-content">
        <header><h5>{{ resourceContent.uri }}</h5><button type="button" class="btn" data-test="cap-resource-close" @click="emit('close-resource')">关闭</button></header>
        <pre>{{ resourceContent.text }}</pre>
      </div>
    </div>
    <div v-else data-test="cap-prompts">
      <p v-if="!prompts.length" class="muted">暂无 prompt。</p>
      <ul v-else class="cap-list">
        <li v-for="p in prompts" :key="p.name" :data-test="`cap-prompt-${p.name}`">
          <span class="cap-name">{{ p.name }}</span>
          <span class="muted cap-desc">{{ p.description }}</span>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.cap { margin-top: 18px; }
.cap-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--color-border); margin-bottom: 14px; }
.cap-tabs button { border: none; background: none; padding: 8px 12px; margin-bottom: -1px; border-bottom: 2px solid transparent; color: var(--color-text-secondary); font-size: 14px; }
.cap-tabs button:hover { color: var(--color-text); }
.cap-tabs button.active { color: var(--color-text); font-weight: 700; border-bottom-color: var(--color-accent); }
.cap-tabs button:focus-visible { outline: none; box-shadow: var(--focus-ring); border-radius: 6px 6px 0 0; }
.cap-list { list-style: none; margin: 0; padding: 0; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.cap-list li + li { border-top: 1px solid var(--color-border); }
.cap-row { width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; background: none; border: none; padding: 10px 14px; cursor: pointer; text-align: left; }
.cap-row:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.cap-name { font-weight: 600; font-family: ui-monospace, monospace; font-size: 13px; }
.cap-desc { font-size: 12px; }
.cap-detail { padding: 0 14px 12px; }
.cap-detail h5 { margin: 8px 0 4px; font-size: 12px; color: var(--color-text-secondary); }
.cap-detail pre { margin: 0; padding: 8px 10px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; font-size: 12px; max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.res-read { margin-top: 12px; border: 1px solid var(--color-border); border-radius: 10px; padding: 12px 14px; background: var(--color-surface); }
.res-read header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.res-read h5 { margin: 0; font-size: 13px; word-break: break-all; }
.res-read pre { margin: 10px 0 0; max-height: 320px; overflow: auto; font-size: 12px; white-space: pre-wrap; word-break: break-word; }
</style>
