<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import AppIcon from '@/components/AppIcon.vue'
import ProjectCreateForm from '@/components/ProjectCreateForm.vue'
import UiConfirmDialog from '@/components/ui/UiConfirmDialog.vue'
import { formatProjectCreatedAt } from '@/presentation/projectPresentation'
import { useProjectStore } from '@/stores/projectStore'
const router = useRouter()
const projectStore = useProjectStore()
const menuOpenId = ref<string | null>(null)
const pendingDelete = ref<{ id: string; title: string } | null>(null)
const deleteError = ref<string | null>(null)
onMounted(() => { void projectStore.loadProjects() })
async function handleCreate(title: string): Promise<void> {
  const project = await projectStore.createProject(title)
  if (project) { await router.push(`/projects/${project.id}`) }
}
function toggleMenu(id: string): void { menuOpenId.value = menuOpenId.value === id ? null : id }
function askDelete(id: string, title: string): void { pendingDelete.value = { id, title }; deleteError.value = null; menuOpenId.value = null }
async function confirmDelete(): Promise<void> {
  if (!pendingDelete.value || projectStore.deletingId) return
  deleteError.value = null
  const ok = await projectStore.deleteProject(pendingDelete.value.id)
  if (ok) { pendingDelete.value = null }
  else { deleteError.value = projectStore.error ? projectStore.error.message : '删除失败，请稍后重试。' }
}
</script>
<template>
  <div class="projects-page">
    <div class="projects-page__heading">
      <h1>项目</h1>
      <p>继续上次的工作，或开始一个新项目。</p>
    </div>
    <ApiErrorBanner v-if="projectStore.error" :message="projectStore.error.message" :code="projectStore.error.code" retry-label="重试" :retrying="projectStore.loading" @retry="projectStore.loadProjects()" />
    <div class="projects-create">
      <ProjectCreateForm :creating="projectStore.creating" @create="handleCreate" />
    </div>
    <p v-if="projectStore.loading" class="muted">正在加载项目…</p>
    <div v-else-if="projectStore.projects.length === 0" class="empty-state">
      <strong>还没有项目。</strong>
      <span>先在上方创建第一个项目，创建后会出现在列表中。</span>
    </div>
    <div v-else role="list" aria-label="项目列表">
      <div v-for="project in projectStore.projects" :key="project.id" class="project-row" role="listitem">
        <div class="project-row__main">
          <RouterLink class="project-row__title" :to="`/projects/${project.id}`">{{ project.title }}</RouterLink>
          <div class="project-row__meta meta-text">创建于 {{ formatProjectCreatedAt(project.createdAt) }}</div>
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
          <button type="button" class="project-row__menu-item project-row__menu-item--danger" role="menuitem" :data-test="'delete-project-' + project.id" @click="askDelete(project.id, project.title)">删除项目</button>
        </div>
      </div>
    </div>
    <UiConfirmDialog :open="pendingDelete !== null" :title="pendingDelete ? `删除「${pendingDelete.title}」？` : '删除项目？'" description="将永久删除该项目的路线、节点、答案、状态与 Spec。此操作无法撤销。" confirm-label="删除项目" cancel-label="取消" :loading="projectStore.deletingId !== null" :error="deleteError" test-id="delete-project-confirm" @cancel="pendingDelete = null" @confirm="confirmDelete" />
  </div>
</template>
<style scoped>
.project-row { position: relative; }
.project-row__actions { display: flex; align-items: center; gap: 4px; flex: none; }
.project-row__menu { position: absolute; right: 8px; top: calc(100% - 6px); min-width: 140px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 10px; box-shadow: var(--shadow-float); padding: 4px; z-index: 5; }
.project-row__menu-item { width: 100%; text-align: left; border: 0; background: transparent; padding: 8px 10px; border-radius: 8px; font-size: 13px; color: var(--color-text); }
.project-row__menu-item:hover:not(:disabled) { background: var(--color-surface-subtle); }
.project-row__menu-item--danger { color: var(--color-danger); }
.project-row__menu-item--danger:hover:not(:disabled) { background: var(--color-danger-soft); }
</style>
