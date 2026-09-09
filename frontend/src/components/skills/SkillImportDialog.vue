<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { SkillsStoreError } from '@/stores/skillsStore'

const props = defineProps<{
  open: boolean
  staging: boolean
  error: SkillsStoreError | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'submit-zip', file: File): void
  (e: 'submit-git', url: string, ref?: string): void
}>()

const mode = ref<'zip' | 'git'>('zip')
const gitUrl = ref('')
const gitRef = ref('')
const fileName = ref('')
let picked: File | null = null

watch(() => props.open, (v) => {
  if (v) {
    mode.value = 'zip'
    gitUrl.value = ''
    gitRef.value = ''
    fileName.value = ''
    picked = null
  }
})

function onFile(e: Event): void {
  const input = e.target as HTMLInputElement
  picked = input.files && input.files[0] ? input.files[0] : null
  fileName.value = picked ? picked.name : ''
}

function submitZip(): void {
  if (picked) emit('submit-zip', picked)
}

function submitGit(): void {
  const url = gitUrl.value.trim()
  if (!url) return
  const ref = gitRef.value.trim()
  emit('submit-git', url, ref ? ref : undefined)
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}
watch(() => props.open, (v) => {
  if (v) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="open" class="import-veil" data-test="import-dialog">
    <div class="import-card" role="dialog" aria-modal="true" aria-label="Add Skill">
      <header class="import-head">
        <h3>添加 Skill</h3>
        <button type="button" class="icon-btn" data-test="close-import" aria-label="关闭" @click="emit('close')"><svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></button>
      </header>
      <div class="import-tabs" role="tablist">
        <button type="button" role="tab" :aria-selected="mode === 'zip'" class="import-tab" :class="{ active: mode === 'zip' }" data-test="import-tab-zip" @click="mode = 'zip'">从 ZIP 导入</button>
        <button type="button" role="tab" :aria-selected="mode === 'git'" class="import-tab" :class="{ active: mode === 'git' }" data-test="import-tab-git" @click="mode = 'git'">从 Git 导入</button>
      </div>
      <div v-if="mode === 'zip'" class="import-pane">
        <label class="settings-field" for="skill-zip-file">
          <span class="settings-field__label">Skill ZIP 包</span>
          <span class="settings-field__hint">选择包含 SKILL.md 的 zip 文件，仅解析不执行其中脚本。</span>
          <input id="skill-zip-file" type="file" accept=".zip,application/zip" data-test="zip-file" @change="onFile" />
        </label>
        <p v-if="fileName" class="muted" data-test="zip-file-name">{{ fileName }}</p>
        <button type="button" class="btn btn-primary" data-test="stage-zip" :disabled="!picked || staging" @click="submitZip">{{ staging ? '正在解析…' : '解析并暂存' }}</button>
      </div>
      <div v-else class="import-pane">
        <label class="settings-field" for="skill-git-url">
          <span class="settings-field__label">Git HTTPS 地址</span>
          <input id="skill-git-url" v-model="gitUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" placeholder="https://..." data-test="git-url" />
        </label>
        <label class="settings-field" for="skill-git-ref">
          <span class="settings-field__label">分支或标签（可选）</span>
          <input id="skill-git-ref" v-model="gitRef" class="settings-control" type="text" autocomplete="off" placeholder="main" data-test="git-ref" />
        </label>
        <button type="button" class="btn btn-primary" data-test="stage-git" :disabled="!gitUrl.trim() || staging" @click="submitGit">{{ staging ? '正在解析…' : '解析并暂存' }}</button>
      </div>
      <p v-if="error" class="error-banner" data-test="import-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    </div>
  </div>
</template>

<style scoped>
.import-veil { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-start; justify-content: center; padding: 72px 16px 16px; background: rgb(23 28 40 / 42%); }
.import-card { width: 100%; max-width: 520px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 14px; padding: 20px 22px; box-shadow: var(--shadow-float); }
.import-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.import-head h3 { margin: 0; font-size: 18px; }
.import-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.import-tab { padding: 7px 12px; border: 1px solid var(--color-border); border-radius: 999px; background: var(--color-surface); color: var(--color-text-secondary); }
.import-tab.active { background: var(--color-accent-soft); border-color: var(--color-accent); color: var(--color-accent-strong); font-weight: 600; }
.import-pane { display: flex; flex-direction: column; gap: 12px; }
.error-banner { margin-top: 12px; }
</style>
