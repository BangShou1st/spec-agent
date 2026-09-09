<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { managementErrorMessage } from '@/api/errorCopy'
import SkillsList from '@/components/skills/SkillsList.vue'
import SkillImportDialog from '@/components/skills/SkillImportDialog.vue'
import SkillImportReview from '@/components/skills/SkillImportReview.vue'
import { useSkillsStore } from '@/stores/skillsStore'

const store = useSkillsStore()
const router = useRouter()

const importOpen = ref(false)
const reviewId = ref<string | null>(null)
const busyId = ref<string | null>(null)

function formatSize(bytes: number): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

function shortIdentity(v: string): string {
  return v.length > 48 ? v.slice(0, 48) : v
}

onMounted(() => {
  void store.loadList()
  void store.loadStaged()
})

function openImport(): void {
  importOpen.value = true
}

async function submitZip(file: File): Promise<void> {
  const ok = await store.stageZip(file)
  if (ok && store.lastStaged) {
    importOpen.value = false
    reviewId.value = store.lastStaged.stagedImportId
    void store.loadStagedDetail(store.lastStaged.stagedImportId)
  }
}

async function submitGit(url: string, ref?: string): Promise<void> {
  const ok = await store.stageGit(url, ref)
  if (ok && store.lastStaged) {
    importOpen.value = false
    reviewId.value = store.lastStaged.stagedImportId
    void store.loadStagedDetail(store.lastStaged.stagedImportId)
  }
}

function openReview(id: string): void {
  reviewId.value = id
  void store.loadStagedDetail(id)
}

async function install(): Promise<void> {
  if (!reviewId.value) return
  const ok = await store.installStaged(reviewId.value)
  if (ok) reviewId.value = null
}

async function reject(): Promise<void> {
  if (!reviewId.value) return
  const ok = await store.rejectStaged(reviewId.value)
  if (ok) reviewId.value = null
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

function select(id: string): void {
  void router.push(`/settings/skills/${encodeURIComponent(id)}`)
}

function retry(): void {
  void store.loadList()
}
</script>

<template>
  <section class="mgmt-page" data-test="skills-page">
    <header class="mgmt-head">
      <div>
        <h2>Skills</h2>
        <p class="muted">安装与管理可用的 Skill，扩展代理的能力边界。</p>
      </div>
      <button type="button" class="btn btn-primary" data-test="add-skill" @click="openImport">+ 添加 Skill</button>
    </header>

    <p v-if="store.error && !store.list.length && !store.listLoading" class="error-banner" data-test="skills-error">
      <span>{{ managementErrorMessage(store.error.code, store.error.message) }}</span>
      <button type="button" class="btn" data-test="skills-retry" @click="retry">重试</button>
    </p>

    <div v-if="store.listLoading" class="muted" data-test="skills-loading">加载中…</div>
    <p v-else-if="!store.list.length && !store.error" class="mgmt-empty" data-test="skills-empty">还没有安装 Skill，点击右上角添加。</p>
    <SkillsList
      v-else
      :skills="store.list"
      :loading="store.listLoading"
      :busy-id="busyId"
      @select="select"
      @enable="enable"
      @disable="disable"
      @remove="remove"
    />
    <p v-if="store.error && store.list.length" class="mgmt-inline-error" data-test="skills-action-error">{{ managementErrorMessage(store.error.code, store.error.message) }}</p>

    <section v-if="store.staged.length" class="staged" data-test="staged-section">
      <h3>待确认导入</h3>
      <ul>
        <li v-for="s in store.staged" :key="s.stagedImportId" :data-test="`staged-row-${s.stagedImportId}`">
          <div class="staged-main">
            <span class="staged-id">{{ shortIdentity(s.sourceIdentity) }}</span>
            <span class="muted">{{ s.sourceKind }} · {{ s.fileCount }} 个文件 · {{ formatSize(s.totalBytes) }}</span>
          </div>
          <button type="button" class="btn" :data-test="`review-staged-${s.stagedImportId}`" @click="openReview(s.stagedImportId)">查看并安装</button>
        </li>
      </ul>
    </section>

    <SkillImportDialog
      :open="importOpen"
      :staging="store.actionLoading"
      :error="store.error"
      @close="importOpen = false"
      @submit-zip="submitZip"
      @submit-git="submitGit"
    />
    <SkillImportReview
      :open="reviewId !== null"
      :staged="store.lastStaged"
      :detail="store.stagedDetail"
      :working="store.actionLoading"
      :error="store.error"
      @close="reviewId = null"
      @install="install"
      @reject="reject"
    />
  </section>
</template>

<style scoped>
.mgmt-page { width: 100%; max-width: 880px; margin: 0 auto; padding: 8px 0 48px; }
.mgmt-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.mgmt-head h2 { margin: 0; font-size: 24px; }
.mgmt-head .muted { margin: 6px 0 0; }
.mgmt-empty { padding: 28px; text-align: center; color: var(--color-text-secondary); background: var(--color-surface); border: 1px dashed var(--color-border-strong); border-radius: 12px; }
.mgmt-inline-error { color: var(--color-danger); font-size: 13px; }
.error-banner { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.staged { margin-top: 28px; }
.staged h3 { font-size: 15px; margin: 0 0 10px; }
.staged ul { list-style: none; margin: 0; padding: 0; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.staged li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; }
.staged li + li { border-top: 1px solid var(--color-border); }
.staged-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.staged-id { font-weight: 600; word-break: break-all; }
</style>
