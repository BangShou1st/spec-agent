<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
export interface GaResourceRef { kind: string; id: string; label: string; metadata?: Record<string, unknown> | null }
const props = defineProps<{ resources: GaResourceRef[] }>()
const router = useRouter()
const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const items = computed(() => (props.resources ?? []).filter((r) => r && r.kind === 'PROJECT' && typeof r.id === 'string' && UUID_RE.test(r.id) && typeof r.label === 'string' && r.label.length > 0).slice(0, 10))
function updatedAtOf(r: GaResourceRef): string {
  const m = (r.metadata ?? {}) as Record<string, unknown>
  const v = m['updatedAt']
  if (typeof v !== 'string' || !v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v.slice(0, 32)
  return d.toLocaleString()
}
async function openProject(id: string): Promise<void> { await router.push('/projects/' + id) }
</script>
<template>
  <div v-if="items.length" class="ga-resources" data-test="ga-tool-resources">
    <p class="ga-resources__title">相关项目</p>
    <ul class="ga-resources__list">
      <li v-for="r in items" :key="r.id" class="ga-resources__item">
        <button type="button" class="ga-resources__btn" :data-test="'ga-resource-' + r.id" @click="openProject(r.id)">
          <span class="ga-resources__label">{{ r.label }}</span>
          <span v-if="updatedAtOf(r)" class="ga-resources__meta">更新于 {{ updatedAtOf(r) }}</span>
        </button>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.ga-resources { margin: 8px 0; border: 1px solid var(--color-border); border-radius: 10px; background: var(--color-surface); padding: 8px; }
.ga-resources__title { margin: 0 0 6px; font-size: 12px; font-weight: 600; color: var(--color-text-secondary); }
.ga-resources__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.ga-resources__btn { width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; text-align: left; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-surface-subtle); padding: 8px 10px; }
.ga-resources__btn:hover:not(:disabled) { border-color: var(--color-accent); background: var(--color-accent-soft); }
.ga-resources__btn:focus-visible { outline: none; box-shadow: var(--focus-ring); border-color: var(--color-focus); }
.ga-resources__label { font-size: 13px; font-weight: 600; color: var(--color-text); overflow-wrap: anywhere; }
.ga-resources__meta { font-size: 12px; color: var(--color-text-muted); }
</style>
