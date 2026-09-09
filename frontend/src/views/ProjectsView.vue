<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ProjectCreateForm from '@/components/ProjectCreateForm.vue'
import { useProjectStore } from '@/stores/projectStore'

const router = useRouter()
const projectStore = useProjectStore()

onMounted(() => {
  void projectStore.loadProjects()
})

async function handleCreate(title: string): Promise<void> {
  const project = await projectStore.createProject(title)
  if (project) {
    await router.push(`/projects/${project.id}`)
  }
}
</script>

<template>
  <div class="projects-page">
    <div class="projects-page__heading">
      <h1>项目</h1>
      <p>继续上次的工作，或开始一个新项目。</p>
    </div>

    <ApiErrorBanner
      v-if="projectStore.error"
      :message="projectStore.error.message"
      :code="projectStore.error.code"
      retry-label="重试"
      :retrying="projectStore.loading"
      @retry="projectStore.loadProjects()"
    />

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
          <div class="project-row__meta meta-text">创建于 {{ project.createdAt }}</div>
        </div>
        <RouterLink class="project-row__open" :to="`/projects/${project.id}`" :aria-label="'打开项目 ' + project.title">打开</RouterLink>
      </div>
    </div>
  </div>
</template>
