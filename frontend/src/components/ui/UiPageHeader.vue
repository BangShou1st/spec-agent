<script setup lang="ts">
defineProps<{ title: string; description?: string | null }>()
</script>
<template>
  <header class="ui-page-header">
    <h1 class="ui-page-header__title">{{ title }}</h1>
    <p v-if="description" class="ui-page-header__desc">{{ description }}</p>
  </header>
</template>
<style scoped>
.ui-page-header { margin: 0 0 16px; }
.ui-page-header__title { margin: 0 0 4px; font-size: 24px; line-height: 1.25; letter-spacing: -0.015em; font-weight: 650; }
.ui-page-header__desc { margin: 0; font-size: 13px; line-height: 1.55; color: var(--color-text-secondary); max-width: 68ch; }
</style>
