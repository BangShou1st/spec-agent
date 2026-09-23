<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { managementErrorMessage } from '@/shared/http/errorCopy'
import AppIcon from '@/shared/ui/AppIcon.vue'
import BackLink from '@/shared/ui/BackLink.vue'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import SkillResourceViewer from '@/features/skills/components/SkillResourceViewer.vue'
import ToggleSwitch from '@/shared/ui/ToggleSwitch.vue'
import UiConfirmDialog from '@/shared/ui/UiConfirmDialog.vue'
import { formatBytes, formatDateTime, skillSourceLabel } from '@/shared/lib/managementCopy'
import { useSkillsStore } from '@/features/skills/state/skillsStore'

const props = defineProps<{ skillId: string }>()
const store = useSkillsStore()
const router = useRouter()

const confirmingDelete = ref(false)
const busy = ref(false)

const working = computed(() => busy.value || store.actionLoading)
const currentVersion = computed(() => store.versions[0] ?? null)
const olderVersions = computed(() => store.versions.slice(1))
const instruction = computed(
  () => store.resources.find((r) => r.path.toUpperCase() === 'SKILL.MD') ?? null,
)
const attachments = computed(() => store.resources.filter((r) => r !== instruction.value))

function load(): void {
  confirmingDelete.value = false
  void store.loadDetail(props.skillId)
}

onMounted(load)
watch(() => props.skillId, load)

function readResource(path: string): void {
  void store.readResource(props.skillId, path)
}

async function toggle(): Promise<void> {
  if (!store.detail) return
  busy.value = true
  await (store.detail.enabled ? store.disable(props.skillId) : store.enable(props.skillId))
  busy.value = false
}

async function remove(): Promise<void> {
  busy.value = true
  const ok = await store.remove(props.skillId)
  busy.value = false
  if (ok) void router.push('/settings/skills')
}
</script>

<template>
  <section class="mgmt-page" data-test="skill-detail-page">
    <BackLink to="/settings/skills" label="返回 Skills" test-id="back-to-skills" />
    <p v-if="store.detailLoading" class="muted" data-test="skill-detail-loading">加载中…</p>
    <ApiErrorBanner
      v-else-if="store.error && !store.detail"
      :message="managementErrorMessage(store.error.code, store.error.message)"
      retry-label="重试"
      data-test="skill-detail-error"
      @retry="load"
    />
    <template v-else-if="store.detail">
      <header class="detail-head">
        <div class="detail-title">
          <h2 data-test="skill-detail-name">{{ store.detail.name }}</h2>
          <p class="detail-desc" data-test="skill-detail-desc">{{ store.detail.description }}</p>
          <p class="detail-meta">
            <span data-test="skill-detail-source">{{ skillSourceLabel(store.detail.sourceKind) }}</span>
            <span data-test="skill-detail-version">{{ currentVersion ? `v${currentVersion.versionNo}` : '—' }}</span>
            <span v-if="currentVersion">{{ currentVersion.fileCount }} 个文件 · {{ formatBytes(currentVersion.totalBytes) }}</span>
          </p>
        </div>
        <div class="detail-actions">
          <ToggleSwitch
            :checked="store.detail.enabled"
            :disabled="working"
            :title="store.detail.enabled ? '停用该 Skill' : '启用该 Skill'"
            :data-test="store.detail.enabled ? 'skill-detail-disable' : 'skill-detail-enable'"
            status-test-id="skill-detail-status"
            @toggle="toggle"
          />
          <button type="button" class="icon-btn" title="删除该 Skill" data-test="skill-detail-delete" :disabled="working" @click="confirmingDelete = true">
            <AppIcon name="trash" />
          </button>
        </div>
      </header>
      <UiConfirmDialog
        :open="confirmingDelete"
        title="删除该 Skill？"
        description="删除该 Skill 及其全部版本？此操作不可撤销"
        confirm-label="确认删除"
        :loading="working"
        test-id="skill-detail-delete-dialog"
        confirm-test-id="skill-detail-delete-confirm"
        cancel-test-id="skill-detail-delete-cancel"
        @confirm="remove"
        @cancel="confirmingDelete = false"
      />
      <p v-if="store.error" class="mgmt-inline-error" data-test="skill-detail-action-error">{{ managementErrorMessage(store.error.code, store.error.message) }}</p>

      <section class="detail-section">
        <h3>技能指令</h3>
        <p v-if="!instruction" class="muted">该 Skill 没有 SKILL.md</p>
        <ul v-else class="res-list">
          <li>
            <button type="button" class="res-btn" :data-test="`skill-resource-${instruction.path}`" @click="readResource(instruction.path)">
              <span class="res-path">{{ instruction.path }}</span>
              <span class="res-meta">{{ formatBytes(instruction.sizeBytes) }}</span>
            </button>
          </li>
        </ul>
      </section>

      <section v-if="attachments.length" class="detail-section">
        <h3>资源文件</h3>
        <ul class="res-list">
          <li v-for="r in attachments" :key="r.path">
            <button type="button" class="res-btn" :data-test="`skill-resource-${r.path}`" @click="readResource(r.path)">
              <span class="res-path">{{ r.path }}</span>
              <span class="res-meta">{{ formatBytes(r.sizeBytes) }}</span>
            </button>
          </li>
        </ul>
      </section>

      <SkillResourceViewer :resource="store.resourceRead" :loading="store.actionLoading && !store.resourceRead" />

      <section class="detail-section">
        <h3>版本</h3>
        <ul class="ver-list">
          <li v-if="currentVersion" :data-test="`skill-version-${currentVersion.versionNo}`">
            <span>v{{ currentVersion.versionNo }}</span>
            <span class="ver-current">当前</span>
            <span class="muted">{{ formatDateTime(currentVersion.createdAt) }}</span>
          </li>
        </ul>
        <details v-if="olderVersions.length" class="ver-history">
          <summary>历史版本（{{ olderVersions.length }}）</summary>
          <ul class="ver-list">
            <li v-for="v in olderVersions" :key="v.id" :data-test="`skill-version-${v.versionNo}`">
              <span>v{{ v.versionNo }}</span>
              <span class="muted">{{ v.fileCount }} 个文件 · {{ formatBytes(v.totalBytes) }} · {{ formatDateTime(v.createdAt) }}</span>
            </li>
          </ul>
        </details>
      </section>
    </template>
  </section>
</template>

<style scoped>
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.detail-title { min-width: 0; }
.detail-title h2 { margin: 0; font-size: 24px; }
.detail-desc { margin: 6px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.detail-meta { display: flex; align-items: center; gap: 8px; margin: 10px 0 0; color: var(--color-text-muted); font-size: 12px; }
.detail-meta span + span::before { content: '·'; margin-right: 8px; }
.detail-actions { flex: none; display: flex; align-items: center; gap: 8px; }
.icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 8px; background: none; border: 1px solid var(--color-border); color: var(--color-danger); cursor: pointer; transition: background 120ms ease, border-color 120ms ease; }
.icon-btn:hover:not(:disabled) { border-color: var(--color-danger); background: rgb(220 38 38 / 10%); }
.icon-btn:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.icon-btn:disabled { opacity: 0.5; cursor: progress; }
.delete-warning { margin: 10px 0 0; color: var(--color-danger); font-size: 13px; }
.detail-section { margin-top: 24px; }
.detail-section h3 { font-size: 15px; margin: 0 0 10px; }
.res-list, .ver-list { list-style: none; margin: 0; padding: 0; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.res-list li + li, .ver-list li + li { border-top: 1px solid var(--color-border); }
.res-btn { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 12px; background: none; border: none; padding: 10px 14px; cursor: pointer; text-align: left; }
.res-btn:hover { background: var(--color-surface-subtle); }
.res-btn:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.res-path { font-size: 13px; word-break: break-all; }
.res-meta { flex: none; color: var(--color-text-muted); font-size: 12px; }
.ver-list li { display: flex; align-items: center; gap: 10px; padding: 10px 14px; font-size: 13px; }
.ver-current { color: var(--color-success); font-size: 12px; font-weight: 700; }
.ver-history { margin-top: 10px; }
.ver-history summary { cursor: pointer; color: var(--color-text-secondary); font-size: 13px; }
</style>
