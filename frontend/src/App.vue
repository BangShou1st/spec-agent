<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useRoute } from 'vue-router'
import GlobalAssistantShell from '@/components/global-assistant/GlobalAssistantShell.vue'

const route = useRoute()
const isWorkspaceRoute = computed(() => route.name === 'workspace')
</script>

<template>
  <header class="app-header" :class="{ 'app-header--workspace': isWorkspaceRoute }">
    <span class="app-name">Spec Agent</span>
    <RouterLink to="/projects">项目</RouterLink>
    <RouterLink to="/settings">设置</RouterLink>
    <span class="app-header__spacer" />
    <GlobalAssistantShell />
  </header>
  <main class="page" :class="{ 'page--workspace': isWorkspaceRoute }">
    <RouterView />
  </main>
</template>

<style scoped>
.app-header__spacer { flex: 1; }
</style>
