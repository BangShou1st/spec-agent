<script setup lang="ts">
import { computed } from 'vue'
import type { ProviderState } from '@/presentation/providerPresentation'
import { stateLabel } from '@/presentation/providerPresentation'

/**
 * Single source for provider status pills across all provider cards.
 * Every card maps its own domain state onto the shared ProviderState,
 * so visual variants can never drift between cards.
 */
const props = defineProps<{ state: ProviderState; testId?: string }>()
const label = computed(() => stateLabel(props.state))
const variantClass = computed(() => {
  switch (props.state) {
    case 'active':
      return 'settings-status--active'
    case 'valid-inactive':
      return 'settings-status--configured'
    case 'configured-unvalidated':
      return 'settings-status--warning'
    case 'invalid':
      return 'settings-status--error'
    default:
      return 'settings-status--empty'
  }
})
</script>

<template>
  <span class="settings-status" :class="variantClass" :data-test="testId ?? 'provider-state'">
    <span class="settings-status__dot" aria-hidden="true"></span>
    {{ label }}
  </span>
</template>
