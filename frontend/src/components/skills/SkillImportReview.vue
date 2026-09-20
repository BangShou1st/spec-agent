<script setup lang="ts">
import { computed } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import UiDialogShell from '@/components/ui/UiDialogShell.vue'
import ResourceBody from '@/components/ResourceBody.vue'
import { managementErrorMessage } from '@/api/errorCopy'
import { formatBytes } from '@/presentation/managementCopy'
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

const manifestPreview = computed(() => {
  const m = props.detail?.manifest ?? ''
  return m.length > 2000 ? m.slice(0, 2000) : m
})

const manifestClipped = computed(() => (props.detail?.manifest ?? '').length > 2000)
const displayName = computed(() => {
  if (props.staged?.name) return props.staged.name
  const identity = props.detail?.sourceIdentity ?? ''
  const tail = identity.split('/').pop()?.split('\\').pop() ?? ''
  const clean = tail.replace(/\.(git|zip)$/i, '')
  return clean || identity || 'staged import'
})
</script>

<template>
  <UiDialogShell
    :open="open"
    title="确认导入"
    test-id="staged-review"
    :max-width="600"
    @close="emit('close')"
  >
    <p class="review-name" data-test="staged-name">{{ displayName }}</p>
    <p class="muted" data-test="staged-desc">{{ staged?.description }}</p>
    <dl class="review-meta" data-test="staged-meta">
      <div><dt>来源</dt><dd>{{ detail?.sourceKind }} · {{ detail?.sourceIdentity }}</dd></div>
      <div><dt>文件数</dt><dd>{{ staged?.fileCount ?? detail?.fileCount }}</dd></div>
      <div><dt>大小</dt><dd>{{ formatBytes(staged?.totalBytes ?? detail?.totalBytes ?? 0) }}</dd></div>
    </dl>
    <section class="review-manifest">
      <h4>SKILL.md</h4>
      <!-- 导入评审要看的是源文件原文，因此走 ResourceBody 的纯文本模式（不传 path）。 -->
      <div class="review-manifest__pre" data-test="staged-manifest">
        <ResourceBody :content="manifestPreview || '（暂无预览）'" />
      </div>
      <p v-if="manifestClipped" class="muted">内容较长，仅显示前 2000 字</p>
    </section>
    <details class="review-tech">
      <summary>技术详情</summary>
      <dl>
        <div><dt>content hash</dt><dd class="meta-text">{{ staged?.contentHash }}</dd></div>
        <div><dt>source identity</dt><dd class="meta-text">{{ detail?.sourceIdentity }}</dd></div>
        <div><dt>status</dt><dd class="meta-text">{{ detail?.status }}</dd></div>
      </dl>
    </details>
    <ApiErrorBanner v-if="error" :message="managementErrorMessage(error.code, error.message)" data-test="staged-error" />
    <template #actions>
      <button type="button" class="btn btn-primary" data-test="install-staged" :disabled="working" @click="emit('install')">{{ working ? '正在安装…' : '安装' }}</button>
      <button type="button" class="btn" data-test="reject-staged" :disabled="working" @click="emit('reject')">拒绝</button>
    </template>
  </UiDialogShell>
</template>

<style scoped>
.review-name { margin: 0; font-size: 17px; font-weight: 700; }
.review-meta { display: grid; gap: 8px; margin: 16px 0; padding: 12px 14px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; }
.review-meta div { display: grid; grid-template-columns: 72px 1fr; gap: 8px; }
.review-meta dt { color: var(--color-text-muted); font-size: 12px; }
.review-meta dd { margin: 0; font-size: 13px; word-break: break-all; }
.review-manifest h4 { margin: 0 0 6px; font-size: 13px; }
.review-manifest__pre { max-height: 220px; overflow: auto; padding: 10px 12px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; font-size: 12px; }
.review-tech { margin-top: 12px; }
.review-tech summary { cursor: pointer; color: var(--color-text-secondary); font-size: 13px; }
.muted { color: var(--color-text-muted); font-size: 13px; overflow-wrap: anywhere; }
</style>
