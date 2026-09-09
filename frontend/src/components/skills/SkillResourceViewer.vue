<script setup lang="ts">
import type { SkillResourceRead } from '@/api/skillTypes'

defineProps<{
  resource: SkillResourceRead | null
  loading: boolean
}>()
</script>

<template>
  <section v-if="resource || loading" class="res-view" data-test="resource-viewer">
    <p v-if="loading" class="muted">读取中…</p>
    <template v-else-if="resource">
      <h4 data-test="resource-path">{{ resource.relativePath }}</h4>
      <p v-if="resource.truncated" class="res-truncated" data-test="resource-truncated">内容已截断，仅显示部分内容。</p>
      <pre data-test="resource-content">{{ resource.content }}</pre>
    </template>
  </section>
</template>

<style scoped>
.res-view { margin-top: 12px; padding: 12px 14px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; }
.res-view h4 { margin: 0 0 8px; font-size: 13px; word-break: break-all; }
.res-truncated { color: var(--color-warn); font-size: 13px; font-weight: 600; }
.res-view pre { max-height: 320px; overflow: auto; margin: 0; font-size: 12px; white-space: pre-wrap; word-break: break-word; }
</style>
