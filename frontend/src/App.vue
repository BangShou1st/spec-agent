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
    <span class="app-name" aria-label="Spec Agent">Spec Agent</span>
    <nav class="app-nav" aria-label="全局导航">
      <RouterLink to="/projects">项目</RouterLink>
      <RouterLink to="/settings">设置</RouterLink>
    </nav>
    <span class="app-header__spacer" />
    <GlobalAssistantShell />
  </header>
  <main class="page" :class="{ 'page--workspace': isWorkspaceRoute }">
    <RouterView />
  </main>
</template>

<style scoped>
.app-header__spacer { flex: 1; }
.app-nav { display: flex; align-items: center; gap: 2px; }
</style>
