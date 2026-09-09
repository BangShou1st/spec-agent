<script setup lang="ts">
import { computed } from 'vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { StagedImportDetail, StagedImportView } from '@/api/skillTypes'
import type { SkillsStoreError } from '@/stores/skillsStore'

const props = defineProps<{
  open: boolean
  staged: StagedImportView | null
  detail: StagedImportDetail | null
  working: boolean
  error: SkillsStoreError | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'install'): void
  (e: 'reject'): void
}>()

function formatSize(bytes: number): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const manifestPreview = computed(() => {
  const m = props.detail?.manifest ?? ''
  return m.length > 2000 ? m.slice(0, 2000) : m
})

const manifestClipped = computed(() => (props.detail?.manifest ?? '').length > 2000)

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}
</script>

<template>
  <div v-if="open" class="review-veil" data-test="staged-review" @keydown="onKey">
    <div class="review-card" role="dialog" aria-modal="true" aria-label="Review staged Skill">
      <header class="review-head">
        <h3>确认导入</h3>
        <button type="button" class="btn" data-test="close-review" @click="emit('close')">关闭</button>
      </header>
      <p class="review-name" data-test="staged-name">{{ staged?.name ?? '未命名 Skill' }}</p>
      <p class="muted" data-test="staged-desc">{{ staged?.description }}</p>
      <dl class="review-meta" data-test="staged-meta">
        <div><dt>来源</dt><dd>{{ detail?.sourceKind }} · {{ detail?.sourceIdentity }}</dd></div>
        <div><dt>文件数</dt><dd>{{ staged?.fileCount ?? detail?.fileCount }}</dd></div>
        <div><dt>大小</dt><dd>{{ formatSize(staged?.totalBytes ?? detail?.totalBytes ?? 0) }}</dd></div>
      </dl>
      <section class="review-manifest">
        <h4>SKILL.md</h4>
        <pre data-test="staged-manifest">{{ manifestPreview || '（暂无预览）' }}</pre>
        <p v-if="manifestClipped" class="muted">内容较长，仅显示前 2000 字。</p>
      </section>
      <details class="review-tech">
        <summary>技术详情</summary>
        <dl>
          <div><dt>content hash</dt><dd class="meta-text">{{ staged?.contentHash }}</dd></div>
          <div><dt>source identity</dt><dd class="meta-text">{{ detail?.sourceIdentity }}</dd></div>
          <div><dt>status</dt><dd class="meta-text">{{ detail?.status }}</dd></div>
        </dl>
      </details>
      <div class="review-actions">
        <button type="button" class="btn btn-primary" data-test="install-staged" :disabled="working" @click="emit('install')">{{ working ? '正在安装…' : '安装' }}</button>
        <button type="button" class="btn" data-test="reject-staged" :disabled="working" @click="emit('reject')">拒绝</button>
      </div>
      <p v-if="error" class="error-banner" data-test="staged-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    </div>
  </div>
</template>

<style scoped>
.review-veil { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-start; justify-content: center; padding: 64px 16px 16px; background: rgb(23 28 40 / 42%); }
.review-card { width: 100%; max-width: 600px; max-height: calc(100vh - 120px); overflow-y: auto; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 14px; padding: 20px 22px; box-shadow: var(--shadow-float); }
.review-head { display: flex; align-items: center; justify-content: space-between; }
.review-head h3 { margin: 0; font-size: 18px; }
.review-name { margin: 12px 0 4px; font-size: 17px; font-weight: 700; }
.review-meta { display: grid; gap: 8px; margin: 16px 0; padding: 12px 14px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; }
.review-meta div { display: grid; grid-template-columns: 72px 1fr; gap: 8px; }
.review-meta dt { color: var(--color-text-muted); font-size: 12px; }
.review-meta dd { margin: 0; font-size: 13px; word-break: break-all; }
.review-manifest h4 { margin: 0 0 6px; font-size: 13px; }
.review-manifest pre { max-height: 220px; overflow: auto; margin: 0; padding: 10px 12px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; font-size: 12px; white-space: pre-wrap; word-break: break-word; }
.review-tech { margin-top: 12px; }
.review-tech summary { cursor: pointer; color: var(--color-text-secondary); font-size: 13px; }
.review-actions { display: flex; gap: 10px; margin-top: 16px; }
.error-banner { margin-top: 12px; }
</style>
