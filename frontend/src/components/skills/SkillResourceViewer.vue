<script setup lang="ts">
import ResourceBody from '@/components/ResourceBody.vue'
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
      <header class="res-view__head">
        <h4 data-test="resource-path">{{ resource.relativePath }}</h4>
        <span v-if="resource.truncated" class="res-truncated" data-test="resource-truncated">内容已截断，仅显示部分内容</span>
      </header>
      <!-- 与文档阅读器共用同一个正文渲染：Markdown 成文排版，代码等宽不折行。 -->
      <div class="res-view__body" data-test="resource-content">
        <ResourceBody :path="resource.relativePath" :content="resource.content" />
      </div>
    </template>
  </section>
</template>

<style scoped>
.res-view { margin-top: 12px; padding: 12px 14px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; }
.res-view__head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.res-view__head h4 { margin: 0; font-size: 13px; word-break: break-all; }
.res-truncated { flex: none; color: var(--color-warn); font-size: 12px; font-weight: 600; }
.res-view__body { max-height: 420px; overflow: auto; }
</style>
