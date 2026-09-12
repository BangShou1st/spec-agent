<script setup lang="ts">
defineProps<{ label: string; htmlFor?: string | null; hint?: string | null; error?: string | null; required?: boolean }>()
</script>
<template>
  <div class="ui-field">
    <label v-if="label" class="ui-field__label" :for="htmlFor ?? undefined">{{ label }}<span v-if="required" class="ui-field__req" aria-hidden="true"> *</span></label>
    <div class="ui-field__control"><slot /></div>
    <p v-if="hint && !error" class="ui-field__hint">{{ hint }}</p>
    <p v-if="error" class="ui-field__error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.ui-field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.ui-field__label { font-size: 12px; font-weight: 600; color: var(--color-text-secondary); }
.ui-field__req { color: var(--color-danger); }
.ui-field__control { min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.ui-field__hint { margin: 0; font-size: 12px; line-height: 1.5; color: var(--color-text-muted); }
.ui-field__error { margin: 0; font-size: 12px; line-height: 1.5; color: var(--color-danger); }
</style>
