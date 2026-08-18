<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header class="app-header">
    <span class="app-name">Spec Agent</span>
    <RouterLink to="/projects">Projects</RouterLink>
  </header>
  <main class="page">
    <RouterView />
  </main>
</template>
