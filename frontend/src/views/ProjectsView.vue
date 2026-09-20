<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import AppIcon from '@/components/AppIcon.vue'
import ProjectCreateForm from '@/components/ProjectCreateForm.vue'
import UiConfirmDialog from '@/components/ui/UiConfirmDialog.vue'
import { projectErrorMessage } from '@/api/errorCopy'
import { formatProjectCreatedAt } from '@/presentation/projectPresentation'
import { normalizeQuery, splitTitleSegments } from '@/presentation/projectSearch'
import { useProjectStore } from '@/stores/projectStore'
const router = useRouter()
const projectStore = useProjectStore()
const menuOpenId = ref<string | null>(null)
const pendingDelete = ref<{ id: string; title: string } | null>(null)
const deleteError = ref<string | null>(null)
/**
 * 重命名是行内编辑，不是弹窗：改的是这一行自己的标题，就该在这一行改。
 * 弹窗会把上下文（同一列表里的其它项目、当前位置）挡掉，而这里没有任何
 * 需要切换视图才能完成的操作。
 */
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const renameError = ref<string | null>(null)
const renameInput = ref<HTMLInputElement | null>(null)
const searchQuery = ref('')
/** 后端消息是英文契约，项目页能出现的错误码在这里换成产品文案。 */
const projectErrorText = computed(() =>
  projectStore.error
    ? projectErrorMessage(projectStore.error.code, projectStore.error.message)
    : '',
)

/**
 * Search is server-side: the backend filters by title and returns the matched
 * subset, so the authoritative result set lives there. We only mirror it here
 * and use the query locally to render the highlight — splitTitleSegments needs
 * the raw term, so highlighting stays client-side and never alters the data.
 */
const hasQuery = computed(() => normalizeQuery(searchQuery.value).length > 0)
const searching = computed(() => projectStore.loading && hasQuery)
// 搜索中的圆环只在请求确实变慢时才出现：150ms 内返回的快速搜索完全不闪烁。
// 圆环与清除按钮共用字段内固定宽度的尾部槽位，出现/消失不会推动输入框宽度。
const spinnerVisible = ref(false)
let spinnerTimer: ReturnType<typeof setTimeout> | null = null
watch(searching, (active) => {
  if (spinnerTimer !== null) { clearTimeout(spinnerTimer); spinnerTimer = null }
  if (active) {
    spinnerTimer = setTimeout(() => { spinnerVisible.value = true; spinnerTimer = null }, 150)
  } else {
    spinnerVisible.value = false
  }
})
onBeforeUnmount(() => { if (spinnerTimer !== null) clearTimeout(spinnerTimer) })
function clearSearch(): void { searchQuery.value = '' }
async function handleCreate(title: string): Promise<void> {
  const project = await projectStore.createProject(title)
  if (project) { await router.push(`/projects/${project.id}`) }
}
function toggleMenu(id: string): void { menuOpenId.value = menuOpenId.value === id ? null : id }
function askDelete(id: string, title: string): void { pendingDelete.value = { id, title }; deleteError.value = null; menuOpenId.value = null }

/**
 * 进入某一行的行内编辑态，并立即聚焦、全选，方便直接覆写。
 *
 * `ref` 在 `v-for` 内部会被收集成数组（Vue 3 一贯行为），直接 `.focus()`
 * 会把 TypeError 抛进 nextTick 的浮动 Promise：界面看起来正常，但焦点从未
 * 落到输入框。这里统一取第一个元素。
 */
function focusRenameInput(): void {
  const target = Array.isArray(renameInput.value) ? renameInput.value[0] : renameInput.value
  target?.focus()
  target?.select()
}

function startRename(id: string, title: string): void {
  renamingId.value = id
  renameTitle.value = title
  renameError.value = null
  menuOpenId.value = null
  void nextTick(focusRenameInput)
}

function cancelRename(): void {
  // 保存请求在途时不允许放弃，否则会留下一个已提交、界面却已退出的错觉。
  if (projectStore.renamingId !== null) return
  renamingId.value = null
  renameError.value = null
}

/** 焦点离开整块编辑区即视为放弃修改（点「保存」不会触发，因为它在区内）。 */
function onRenameFocusOut(event: FocusEvent): void {
  const root = event.currentTarget as HTMLElement | null
  const next = event.relatedTarget as Node | null
  if (root && next && root.contains(next)) return
  cancelRename()
}

async function commitRename(): Promise<void> {
  const id = renamingId.value
  if (!id || projectStore.renamingId !== null) return
  const title = renameTitle.value.trim()
  if (!title) { renameError.value = '项目名称不能为空'; return }
  const ok = await projectStore.renameProject(id, title)
  if (ok) {
    renamingId.value = null
    renameError.value = null
  } else {
    renameError.value = projectStore.error
      ? projectErrorMessage(projectStore.error.code, projectStore.error.message)
      : '重命名失败，请稍后重试'
  }
}
async function confirmDelete(): Promise<void> {
  if (!pendingDelete.value || projectStore.deletingId) return
  deleteError.value = null
  const ok = await projectStore.deleteProject(pendingDelete.value.id)
  if (ok) { pendingDelete.value = null }
  else { deleteError.value = projectStore.error ? projectStore.error.message : '删除失败，请稍后重试' }
}

onMounted(() => { void projectStore.loadProjects() })
// Re-query the backend whenever the term changes; the store guards against
// out-of-order responses so only the latest result is shown.
watch(searchQuery, (value) => { void projectStore.loadProjects(normalizeQuery(value)) })
</script>
<template>
  <div class="projects-page">
    <div class="projects-page__heading">
      <h1>项目</h1>
      <p>继续上次的工作，或开始一个新项目</p>
    </div>
    <ApiErrorBanner v-if="projectStore.error" :message="projectErrorText" :code="projectStore.error.code" retry-label="重试" :retrying="projectStore.loading" @retry="projectStore.loadProjects()" />
    <div class="projects-create">
      <ProjectCreateForm :creating="projectStore.creating" @create="handleCreate" />
    </div>
    <p v-if="projectStore.loading && projectStore.projects.length === 0 && !hasQuery" class="muted">正在加载项目…</p>
    <div v-else-if="projectStore.projects.length === 0 && !hasQuery" class="empty-state">
      <strong>还没有项目</strong>
      <span>先在上方创建第一个项目，创建后会出现在列表中</span>
    </div>
    <template v-else>
      <div class="projects-search">
        <label class="projects-search__field">
          <AppIcon name="search" />
          <input
            v-model="searchQuery"
            type="text"
            class="projects-search__input"
            data-test="project-search"
            aria-label="搜索项目"
            placeholder="按名称搜索项目…"
            @keydown.esc.prevent="clearSearch"
          />
          <span class="projects-search__trail">
            <span v-if="spinnerVisible" class="projects-search__spinner" aria-hidden="true" />
            <button v-if="hasQuery && !spinnerVisible" type="button" class="projects-search__clear" data-test="project-search-clear" aria-label="清除搜索" @click="clearSearch">
              <AppIcon name="close" />
            </button>
          </span>
        </label>
      </div>
      <p v-if="hasQuery" class="projects-search__count meta-text" data-test="project-search-count">找到 {{ projectStore.projects.length }} 个匹配项目</p>
      <div v-if="hasQuery && projectStore.projects.length === 0" class="empty-state" data-test="project-search-empty">
        <strong>没有匹配「{{ normalizeQuery(searchQuery) }}」的项目</strong>
        <span>换个关键词，或清除搜索查看全部项目</span>
        <button type="button" class="btn" data-test="project-search-reset" @click="clearSearch">清除搜索</button>
      </div>
      <div v-else role="list" aria-label="项目列表">
        <div v-for="project in projectStore.projects" :key="project.id" class="project-row" role="listitem">
          <div class="project-row__main">
            <div
              v-if="renamingId === project.id"
              class="project-row__rename"
              data-test="rename-project-inline"
              @focusout="onRenameFocusOut"
            >
              <input
                ref="renameInput"
                v-model="renameTitle"
                class="answer-input project-row__rename-input"
                data-test="rename-project-input"
                maxlength="255"
                aria-label="项目新名称"
                :disabled="projectStore.renamingId !== null"
                @keydown.enter.prevent="commitRename"
                @keydown.esc.prevent="cancelRename"
              />
              <div class="project-row__rename-actions">
                <button
                  class="btn btn-small"
                  type="button"
                  data-test="rename-project-cancel"
                  :disabled="projectStore.renamingId !== null"
                  @click="cancelRename"
                >
                  取消
                </button>
                <button
                  class="btn btn-primary btn-small"
                  type="button"
                  data-test="rename-project-confirm"
                  :disabled="projectStore.renamingId !== null || renameTitle.trim().length === 0"
                  @click="commitRename"
                >
                  {{ projectStore.renamingId !== null ? '正在保存…' : '保存' }}
                </button>
              </div>
              <p v-if="renameError" class="projects-rename__error" data-test="rename-project-error">{{ renameError }}</p>
            </div>
            <template v-else>
              <RouterLink class="project-row__title" :to="`/projects/${project.id}`"><template v-for="(segment, index) in splitTitleSegments(project.title, searchQuery)" :key="index"><mark v-if="segment.matched" class="project-row__mark" data-test="project-title-match">{{ segment.text }}</mark><template v-else>{{ segment.text }}</template></template></RouterLink>
              <div class="project-row__meta meta-text">创建于 {{ formatProjectCreatedAt(project.createdAt) }}</div>
            </template>
          </div>
          <div class="project-row__actions">
            <RouterLink class="icon-btn project-row__open" :to="`/projects/${project.id}`" :title="'打开项目 ' + project.title" :aria-label="'打开项目 ' + project.title">
              <AppIcon name="chevron-right" />
              <span class="visually-hidden">打开</span>
            </RouterLink>
            <button type="button" class="icon-btn" :aria-label="'更多操作：' + project.title" :aria-expanded="menuOpenId === project.id ? 'true' : 'false'" aria-haspopup="menu" :data-test="'project-menu-' + project.id" @click="toggleMenu(project.id)">
              <AppIcon name="more" />
            </button>
          </div>
          <div v-if="menuOpenId === project.id" class="project-row__menu" role="menu" :aria-label="'项目操作：' + project.title">
            <button type="button" class="project-row__menu-item" role="menuitem" :data-test="'rename-project-' + project.id" @click="startRename(project.id, project.title)">重命名</button>
            <button type="button" class="project-row__menu-item project-row__menu-item--danger" role="menuitem" :data-test="'delete-project-' + project.id" @click="askDelete(project.id, project.title)">删除项目</button>
          </div>
        </div>
      </div>
    </template>
    <UiConfirmDialog :open="pendingDelete !== null" :title="pendingDelete ? `删除「${pendingDelete.title}」？` : '删除项目？'" description="将永久删除该项目的路线、节点、答案、状态与 Spec。此操作无法撤销" confirm-label="删除项目" cancel-label="取消" :loading="projectStore.deletingId !== null" :error="deleteError" test-id="delete-project-confirm" @cancel="pendingDelete = null" @confirm="confirmDelete" />
  </div>
</template>
<style scoped>
.projects-rename__error { margin: 6px 0 0; font-size: 12px; color: var(--color-danger); }
/* 行内重命名：输入框占满标题位置，动作行落在原「创建于」那一行的高度上，
   列表不会因为进入编辑态而跳动。 */
.project-row__rename { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.project-row__rename-input { width: 100%; }
.project-row__rename-actions { display: flex; gap: 12px; }
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label input { display: block; margin-top: 4px; width: 100%; box-sizing: border-box; }
.projects-search { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.projects-search__field {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  height: var(--control-h);
  padding: 0 4px 0 12px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 10px;
  color: var(--color-text-muted);
  transition: border-color var(--motion-fast) ease, box-shadow var(--motion-fast) ease;
}
.projects-search__field:hover { border-color: var(--color-border-strong); }
.projects-search__field:focus-within { border-color: var(--color-focus); box-shadow: var(--focus-ring); }
.projects-search__input { flex: 1; min-width: 0; border: 0; background: transparent; padding: 0; height: 100%; font: inherit; font-size: 14px; color: var(--color-text); outline: none; box-shadow: none; }
.projects-search__input:focus { outline: none; box-shadow: none; border: 0; }
.projects-search__input::placeholder { color: var(--color-text-muted); }
.projects-search__trail { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; }
.projects-search__clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
}
.projects-search__clear:hover { background: var(--color-subdued); color: var(--color-text); }
.projects-search__clear:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.projects-search__spinner { width: 14px; height: 14px; border: 2px solid var(--color-border); border-top-color: var(--color-accent); border-radius: 50%; animation: project-search-spin 0.7s linear infinite; }
.projects-search__count { margin: 0 0 10px; }
.project-row { position: relative; }
.project-row__mark { background: var(--color-warn-soft); color: inherit; border-radius: 3px; padding: 0 1px; }
.project-row__actions { display: flex; align-items: center; gap: 4px; flex: none; }
.project-row__menu { position: absolute; right: 8px; top: calc(100% - 6px); min-width: 140px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 10px; box-shadow: var(--shadow-float); padding: 4px; z-index: 5; }
.project-row__menu-item { width: 100%; text-align: left; border: 0; background: transparent; padding: 8px 10px; border-radius: 8px; font-size: 13px; color: var(--color-text); }
.project-row__menu-item:hover:not(:disabled) { background: var(--color-surface-subtle); }
.project-row__menu-item--danger { color: var(--color-danger); }
.project-row__menu-item--danger:hover:not(:disabled) { background: var(--color-danger-soft); }
@keyframes project-search-spin { to { transform: rotate(360deg); } }
</style>
