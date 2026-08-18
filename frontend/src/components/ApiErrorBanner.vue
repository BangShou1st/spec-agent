<script setup lang="ts">
/**
 * Compact error presentation for API failures. Only safe backend messages
 * (or the generic frontend fallback) are rendered; raw bodies, stack traces,
 * HTML pages, and provider payloads are never shown. An optional retry
 * re-invokes the user's explicit request (UI retry only).
 */
defineProps<{
  message: string
  code?: string
  retryLabel?: string
  retrying?: boolean
}>()

const emit = defineEmits<{
  retry: []
}>()
</script>

<template>
  <div class="error-banner" role="alert">
    <div>
      <strong v-if="code">{{ code }}</strong>
      <span v-if="code"> — </span>
      {{ message }}
    </div>
    <button
      v-if="retryLabel"
      class="btn"
      type="button"
      :disabled="retrying"
      @click="emit('retry')"
    >
      {{ retrying ? 'Retrying…' : retryLabel }}
    </button>
  </div>
</template>
