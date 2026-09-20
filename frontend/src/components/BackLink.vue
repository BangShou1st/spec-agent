<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'

withDefaults(defineProps<{
  to: string
  label: string
  testId?: string
}>(), { testId: 'back-link' })
</script>

<template>
  <RouterLink :to="to" class="back-link" :data-test="testId">
    <AppIcon name="arrow-left" class="back-link__icon" />
    <span>{{ label }}</span>
  </RouterLink>
</template>

<style scoped>
.back-link { display: inline-flex; align-items: center; gap: 8px; margin: 0 0 12px -8px; padding: 6px 12px 6px 8px; border-radius: 8px; color: var(--color-text-secondary); font-size: 14px; font-weight: 500; text-decoration: none; }
.back-link:hover { background: var(--color-subdued); color: var(--color-text); }
.back-link:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.back-link__icon { flex: none; }
</style>
