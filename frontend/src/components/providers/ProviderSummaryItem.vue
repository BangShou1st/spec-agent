<script setup lang="ts">
/**
 * One label/value row inside a ProviderCard's current-configuration summary.
 * Shared so every provider renders its summary items identically — including
 * the ellipsis rule and the em-dash placeholder for absent values.
 */
withDefaults(defineProps<{
  label: string
  value: string | null | undefined
  testId?: string
  /** Long single-line values (base URL, model id) must not wrap the grid. */
  ellipsis?: boolean
}>(), {
  testId: undefined,
  ellipsis: false,
})
</script>

<template>
  <div class="settings-current-config__item">
    <span>{{ label }}</span>
    <strong
      :class="{ 'provider-card__ellipsis': ellipsis }"
      :title="ellipsis ? (value ?? '') : undefined"
      :data-test="testId"
    >{{ value ?? '—' }}</strong>
  </div>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此组件无自身私有样式。 */
</style>
