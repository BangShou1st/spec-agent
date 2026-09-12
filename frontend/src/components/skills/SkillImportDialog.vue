<script setup lang="ts">
import { ref, watch } from 'vue'
import UiDialogShell from '@/components/ui/UiDialogShell.vue'
import UiFormField from '@/components/ui/UiFormField.vue'
import UiFilePicker from '@/components/ui/UiFilePicker.vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { SkillsStoreError } from '@/stores/skillsStore'
const props = defineProps<{ open: boolean; staging: boolean; error: SkillsStoreError | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'submit-zip', file: File): void; (e: 'submit-git', url: string, ref?: string): void }>()
const mode = ref<'zip' | 'git'>('zip')
const gitUrl = ref('')
const gitRef = ref('')
let picked: File | null = null
const pickedName = ref('')
watch(() => props.open, (v) => { if (v) { mode.value = 'zip'; gitUrl.value = ''; gitRef.value = ''; picked = null; pickedName.value = '' } })
function onPick(f: File): void { picked = f; pickedName.value = f.name }
function submitZip(): void { if (picked) emit('submit-zip', picked) }
function submitGit(): void { const url = gitUrl.value.trim(); if (!url) return; const r = gitRef.value.trim(); emit('submit-git', url, r ? r : undefined) }
</script>
<template>
  <UiDialogShell :open="open" title="添加 Skill" description="从 ZIP 或 Git 导入，仅解析不执行其中脚本。" test-id="import-dialog" @close="emit('close')">
    <div class="import-tabs" role="tablist" aria-label="导入方式">
      <button type="button" role="tab" :aria-selected="mode === 'zip'" class="import-tab" :class="{ active: mode === 'zip' }" data-test="import-tab-zip" @click="mode = 'zip'">从 ZIP 导入</button>
      <button type="button" role="tab" :aria-selected="mode === 'git'" class="import-tab" :class="{ active: mode === 'git' }" data-test="import-tab-git" @click="mode = 'git'">从 Git 导入</button>
    </div>
    <div v-if="mode === 'zip'">
      <UiFormField label="Skill ZIP 包" hint="选择包含 SKILL.md 的 zip 文件，仅解析不执行其中脚本。">
        <UiFilePicker accept=".zip,application/zip" test-id="zip-file" button-label="选择 ZIP" placeholder="尚未选择文件" @pick="onPick" />
      </UiFormField>
      <p v-if="pickedName" class="muted" data-test="zip-file-name">{{ pickedName }}</p>
    </div>
    <div v-else class="import-pane">
      <UiFormField label="Git HTTPS 地址" html-for="skill-git-url">
        <input id="skill-git-url" v-model="gitUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" placeholder="https://..." data-test="git-url" />
      </UiFormField>
      <UiFormField label="分支或标签（可选）" html-for="skill-git-ref">
        <input id="skill-git-ref" v-model="gitRef" class="settings-control" type="text" autocomplete="off" placeholder="main" data-test="git-ref" />
      </UiFormField>
    </div>
    <p v-if="error" class="error-banner" data-test="import-error">{{ managementErrorMessage(error.code, error.message) }}</p>
    <template #actions>
      <button type="button" class="btn btn-secondary" data-test="close-import" @click="emit('close')">取消</button>
      <button v-if="mode === 'zip'" type="button" class="btn btn-primary" data-test="stage-zip" :disabled="!picked || staging" @click="submitZip">{{ staging ? '正在解析…' : '解析并暂存' }}</button>
      <button v-else type="button" class="btn btn-primary" data-test="stage-git" :disabled="!gitUrl.trim() || staging" @click="submitGit">{{ staging ? '正在解析…' : '解析并暂存' }}</button>
    </template>
  </UiDialogShell>
</template>
<style scoped>
.import-tabs { display: flex; gap: 8px; }
.import-tab { padding: 7px 12px; border: 1px solid var(--color-border); border-radius: 999px; background: var(--color-surface); color: var(--color-text-secondary); }
.import-tab.active { background: var(--color-accent-soft); border-color: var(--color-accent); color: var(--color-accent-strong); font-weight: 600; }
.import-pane { display: flex; flex-direction: column; gap: 12px; }
.error-banner { margin: 0; }
.muted { color: var(--color-text-muted); font-size: 13px; margin: 8px 0 0; overflow-wrap: anywhere; }
</style>
