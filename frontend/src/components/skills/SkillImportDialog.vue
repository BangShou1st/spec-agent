<script setup lang="ts">
import { ref, watch } from 'vue'
import UiDialogShell from '@/components/ui/UiDialogShell.vue'
import UiFormField from '@/components/ui/UiFormField.vue'
import UiFilePicker from '@/components/ui/UiFilePicker.vue'
import { managementErrorMessage } from '@/api/errorCopy'
import type { SkillsStoreError } from '@/stores/skillsStore'
import type { GitSkillCandidate } from '@/api/skillTypes'
const props = defineProps<{
  open: boolean
  staging: boolean
  error: SkillsStoreError | null
  discovering?: boolean
  candidates?: GitSkillCandidate[] | null
}>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'submit-zip', file: File): void
  (e: 'submit-git', url: string, ref?: string, subPath?: string): void
  (e: 'discover-git', url: string, ref?: string): void
}>()
const mode = ref<'zip' | 'git'>('zip')
const gitUrl = ref('')
const gitRef = ref('')
const gitSubPath = ref('')
let picked: File | null = null
const pickedName = ref('')
watch(() => props.open, (v) => { if (v) { mode.value = 'zip'; gitUrl.value = ''; gitRef.value = ''; gitSubPath.value = ''; picked = null; pickedName.value = '' } })
function onPick(f: File): void { picked = f; pickedName.value = f.name }
function submitZip(): void { if (picked) emit('submit-zip', picked) }
function submitGit(): void {
  const url = gitUrl.value.trim()
  if (!url) return
  const r = gitRef.value.trim()
  const sub = gitSubPath.value.trim()
  emit('submit-git', url, r ? r : undefined, sub ? sub : undefined)
}
function discoverGit(): void {
  const url = gitUrl.value.trim()
  if (!url) return
  const r = gitRef.value.trim()
  emit('discover-git', url, r ? r : undefined)
}
/** Choosing a candidate is the same selection the subdirectory field carries. */
function pickCandidate(candidate: GitSkillCandidate): void {
  gitSubPath.value = candidate.path
}
</script>
<template>
  <UiDialogShell :open="open" title="添加 Skill" description="从 ZIP 或 Git 导入，仅解析不执行其中脚本" test-id="import-dialog" @close="emit('close')">
    <div class="import-tabs" role="tablist" aria-label="导入方式">
      <button type="button" role="tab" :aria-selected="mode === 'zip'" class="import-tab" :class="{ active: mode === 'zip' }" data-test="import-tab-zip" @click="mode = 'zip'">从 ZIP 导入</button>
      <button type="button" role="tab" :aria-selected="mode === 'git'" class="import-tab" :class="{ active: mode === 'git' }" data-test="import-tab-git" @click="mode = 'git'">从 Git 导入</button>
    </div>
    <div v-if="mode === 'zip'">
      <UiFormField label="Skill ZIP 包" hint="选择包含 SKILL.md 的 zip 文件，仅解析不执行其中脚本">
        <UiFilePicker accept=".zip,application/zip" test-id="zip-file" button-label="选择 ZIP" placeholder="尚未选择文件" @pick="onPick" />
      </UiFormField>
      <p v-if="pickedName" class="muted" data-test="zip-file-name">{{ pickedName }}</p>
    </div>
    <div v-else class="import-pane">
      <UiFormField label="Git HTTPS 地址" html-for="skill-git-url" hint="仓库根目录有 SKILL.md 时可直接导入；多技能或插件市场仓库请用下方按钮挑出技能目录">
        <input id="skill-git-url" v-model="gitUrl" class="settings-control" type="url" inputmode="url" autocomplete="off" placeholder="https://..." data-test="git-url" />
      </UiFormField>
      <UiFormField label="分支或标签（可选）" html-for="skill-git-ref">
        <input id="skill-git-ref" v-model="gitRef" class="settings-control" type="text" autocomplete="off" placeholder="main" data-test="git-ref" />
      </UiFormField>
      <UiFormField label="技能子目录（可选）" html-for="skill-git-sub-path" hint="留空表示仓库根目录就是 Skill 包；否则填技能目录，例如 skills/brainstorming">
        <input id="skill-git-sub-path" v-model="gitSubPath" class="settings-control" type="text" autocomplete="off" placeholder="skills/brainstorming" data-test="git-sub-path" />
      </UiFormField>
      <div class="import-discover">
        <button type="button" class="btn btn-secondary" data-test="discover-git" :disabled="!gitUrl.trim() || discovering" @click="discoverGit">
          {{ discovering ? '正在读取仓库…' : '查看仓库里的技能' }}
        </button>
        <p v-if="candidates" class="muted" data-test="discover-summary">
          仓库中检测到 {{ candidates.length }} 个技能，点选一个即可填入子目录
        </p>
      </div>
      <ul v-if="candidates && candidates.length" class="candidate-list" data-test="candidate-list">
        <li v-for="candidate in candidates" :key="candidate.path">
          <button
            type="button"
            class="candidate"
            :class="{ 'candidate--selected': gitSubPath.trim() === candidate.path }"
            data-test="candidate-option"
            :disabled="!candidate.parseable"
            :title="candidate.description || candidate.path"
            @click="pickCandidate(candidate)"
          >
            <span class="candidate__name">{{ candidate.parseable ? candidate.name : candidate.path + '（SKILL.md 不可解析）' }}</span>
            <span class="candidate__path">{{ candidate.path || '仓库根目录' }} · {{ candidate.fileCount }} 个文件</span>
          </button>
        </li>
      </ul>
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
.import-discover { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.import-discover .muted { margin: 0; }
.candidate-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; max-height: 220px; overflow-y: auto; }
.candidate { display: flex; flex-direction: column; gap: 2px; width: 100%; text-align: left; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 10px; background: var(--color-surface); }
.candidate:hover:not(:disabled) { border-color: var(--color-accent); }
.candidate:focus-visible { outline: none; box-shadow: var(--focus-ring); border-color: var(--color-focus); }
.candidate--selected { border-color: var(--color-accent); background: var(--color-accent-soft); }
.candidate:disabled { opacity: 0.6; cursor: not-allowed; }
.candidate__name { font-size: 13px; color: var(--color-text); overflow-wrap: anywhere; }
.candidate__path { font-size: 11.5px; color: var(--color-text-muted); overflow-wrap: anywhere; }
.error-banner { margin: 0; }
.muted { color: var(--color-text-muted); font-size: 13px; margin: 8px 0 0; overflow-wrap: anywhere; }
</style>
