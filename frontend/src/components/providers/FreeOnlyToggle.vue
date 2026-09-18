<script setup lang="ts">
/**
 * Shared 仅免费 filter toggle used by every provider model list.
 * Checked state is owned by the parent; this only renders and reports.
 */
defineProps<{ checked: boolean; testId?: string }>()
const emit = defineEmits<{ (e: 'change', value: boolean): void }>()
</script>

<template>
  <label class="settings-free-toggle" :data-test="testId ?? 'provider-free-toggle'">
    <input
      type="checkbox"
      :checked="checked"
      @change="emit('change', ($event.target as HTMLInputElement).checked)"
    />
    <span>仅免费</span>
  </label>
</template>
