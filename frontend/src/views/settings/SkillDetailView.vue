<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { managementErrorMessage } from '@/api/errorCopy'
import BackLink from '@/components/BackLink.vue'
import SkillResourceViewer from '@/components/skills/SkillResourceViewer.vue'
import { formatBytes, formatDateTime, skillSourceLabel } from '@/presentation/managementCopy'
import { useSkillsStore } from '@/stores/skillsStore'

const props = defineProps<{ skillId: string }>()
const store = useSkillsStore()
const router = useRouter()

const confirmingDelete = ref(false)
const busy = ref(false)

function load(): void {
  confirmingDelete.value = false
  void store.loadDetail(props.skillId)
}

onMounted(load)
watch(() => props.skillId, load)

function readResource(path: string): void {
  void store.readResource(props.skillId, path)
}

async function enable(): Promise<void> {
  busy.value = true
  await store.enable(props.skillId)
  busy.value = false
}

async function disable(): Promise<void> {
  busy.value = true
  await store.disable(props.skillId)
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
    <p v-else-if="store.error && !store.detail" class="error-banner" data-test="skill-detail-error">
      <span>{{ managementErrorMessage(store.error.code, store.error.message) }}</span>
      <button type="button" class="btn" data-test="skill-detail-retry" @click="load">重试</button>
    </p>
    <template v-else-if="store.detail">
      <header class="detail-head">
        <h2 data-test="skill-detail-name">{{ store.detail.name }}</h2>
        <span class="skills-status" :class="store.detail.enabled ? 'skills-status--on' : 'skills-status--off'" data-test="skill-detail-status">
          <span class="skills-status__dot" aria-hidden="true"></span>{{ store.detail.enabled ? '已启用' : '已禁用' }}</span>
      </header>
      <p class="muted" data-test="skill-detail-desc">{{ store.detail.description }}</p>
      <dl class="detail-meta">
        <div><dt>来源</dt><dd data-test="skill-detail-source">{{ skillSourceLabel(store.detail.sourceKind) }}</dd></div>
        <div><dt>当前版本</dt><dd data-test="skill-detail-version">{{ store.detail.versionId ? store.detail.versionId.slice(0, 8) : '—' }}</dd></div>
      </dl>
      <details class="detail-tech">
        <summary>技术详情</summary>
        <dl>
          <div><dt>skill id</dt><dd class="meta-text">{{ store.detail.skillId }}</dd></div>
          <div><dt>source identity</dt><dd class="meta-text">{{ store.detail.sourceIdentity }}</dd></div>
          <div><dt>version id</dt><dd class="meta-text">{{ store.detail.versionId }}</dd></div>
        </dl>
      </details>
      <div class="detail-actions">
        <button v-if="!store.detail.enabled" type="button" class="btn btn-primary" data-test="skill-detail-enable" :disabled="busy || store.actionLoading" @click="enable">启用</button>
        <button v-else type="button" class="btn" data-test="skill-detail-disable" :disabled="busy || store.actionLoading" @click="disable">禁用</button>
        <button v-if="!confirmingDelete" type="button" class="btn" data-test="skill-detail-delete" :disabled="busy || store.actionLoading" @click="confirmingDelete = true">删除</button>
        <span v-else class="delete-confirm">
          <span>删除该 Skill 及其全部版本？此操作不可撤销。</span>
          <button type="button" class="btn btn-danger" data-test="skill-detail-delete-confirm" :disabled="busy || store.actionLoading" @click="remove">确认删除</button>
          <button type="button" class="btn" data-test="skill-detail-delete-cancel" @click="confirmingDelete = false">取消</button>
        </span>
      </div>
      <p v-if="store.error" class="mgmt-inline-error" data-test="skill-detail-action-error">{{ managementErrorMessage(store.error.code, store.error.message) }}</p>
      <section class="detail-section">
        <h3>资源</h3>
        <p v-if="!store.resources.length" class="muted">暂无资源。</p>
        <ul v-else class="res-list">
          <li v-for="r in store.resources" :key="r.path">
            <button type="button" class="res-btn" :data-test="`skill-resource-${r.path}`" @click="readResource(r.path)">
              <span>{{ r.path }}</span><span class="muted">{{ r.kind }} · {{ formatBytes(r.sizeBytes) }}</span>
            </button>
          </li>
        </ul>
        <SkillResourceViewer :resource="store.resourceRead" :loading="store.actionLoading && !store.resourceRead" />
      </section>
      <section class="detail-section">
        <h3>版本</h3>
        <ul class="ver-list">
          <li v-for="(v, i) in store.versions" :key="v.id" :data-test="`skill-version-${v.versionNo}`">
            <span>版本 {{ v.versionNo }}</span>
            <span v-if="i === 0" class="ver-current">当前</span>
            <span class="muted">{{ v.fileCount }} 个文件 · {{ formatBytes(v.totalBytes) }} · {{ formatDateTime(v.createdAt) }}</span>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.mgmt-page { width: 100%; max-width: 880px; margin: 0 auto; padding: 8px 0 48px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-head h2 { margin: 0; font-size: 24px; }
.skills-status { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.skills-status--on { color: var(--color-success); }
.skills-status--off { color: var(--color-text-secondary); }
.skills-status__dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.detail-meta { display: grid; gap: 8px; margin: 14px 0; }
.detail-meta div { display: grid; grid-template-columns: 72px 1fr; gap: 8px; }
.detail-meta dt { color: var(--color-text-muted); font-size: 12px; }
.detail-meta dd { margin: 0; font-size: 13px; }
.detail-tech { margin: 8px 0 4px; }
.detail-tech summary { cursor: pointer; color: var(--color-text-secondary); font-size: 13px; }
.detail-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 14px 0 4px; }
.delete-confirm { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--color-danger); }
.mgmt-inline-error { color: var(--color-danger); font-size: 13px; }
.error-banner { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-section { margin-top: 24px; }
.detail-section h3 { font-size: 15px; margin: 0 0 10px; }
.res-list, .ver-list { list-style: none; margin: 0; padding: 0; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.res-list li + li, .ver-list li + li { border-top: 1px solid var(--color-border); }
.res-btn { width: 100%; display: flex; justify-content: space-between; gap: 12px; background: none; border: none; padding: 10px 14px; cursor: pointer; text-align: left; }
.res-btn:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.ver-list li { display: flex; align-items: center; gap: 10px; padding: 10px 14px; font-size: 13px; }
.ver-current { color: var(--color-success); font-size: 12px; font-weight: 700; }
</style>
