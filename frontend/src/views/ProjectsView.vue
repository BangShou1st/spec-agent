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
  <div>
    <h1>Projects</h1>

    <ApiErrorBanner
      v-if="projectStore.error"
      :message="projectStore.error.message"
      :code="projectStore.error.code"
      retry-label="Retry"
      :retrying="projectStore.loading"
      @retry="projectStore.loadProjects()"
    />

    <ProjectCreateForm :creating="projectStore.creating" @create="handleCreate" />

    <p v-if="projectStore.loading" class="muted">Loading projects…</p>

    <div v-else-if="projectStore.projects.length === 0" class="empty-state">
      No projects yet. Create your first project above.
    </div>

    <div v-else>
      <div v-for="project in projectStore.projects" :key="project.id" class="project-row">
        <RouterLink :to="`/projects/${project.id}`">{{ project.title }}</RouterLink>
        <span class="meta-text">created {{ project.createdAt }}</span>
      </div>
    </div>
  </div>
</template>