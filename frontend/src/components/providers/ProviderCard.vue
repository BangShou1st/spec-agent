<script setup lang="ts">
import { computed } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ProviderStatePill from '@/components/providers/ProviderStatePill.vue'
import { productErrorMessage } from '@/api/errorCopy'
import type { ProviderState } from '@/presentation/providerPresentation'

/**
 * The provider-card paradigm, extracted from the OpenRouter card and shared by
 * OpenCode Zen, OpenRouter and the user's Custom gateway.
 *
 * A card contributes only its own content through slots — `summary` for the
 * current-configuration items, the default slot for its form/fields and
 * `footer` for its actions. Header, status pill, error banner and the vertical
 * rhythm therefore live in exactly one place and can never drift apart.
 */
const props = defineProps<{
  title: string
  description: string
  state: ProviderState
  cardTestId: string
  /** Error projection owned by the card's own store; null means no banner. */
  error: { code: string } | null
  titleTestId?: string
  stateTestId?: string
  errorTestId?: string
  summaryTestId?: string
  retrying?: boolean
  retryLabel?: string
}>()

const emit = defineEmits<{ (e: 'retry'): void }>()

const safeErrorMessage = computed(() => productErrorMessage(props.error?.code ?? 'UNKNOWN_ERROR'))
</script>

<template>
  <article class="settings-card" :data-test="cardTestId">
    <header class="settings-card__header">
      <div>
        <h3 :data-test="titleTestId">{{ title }}</h3>
        <p class="settings-card__description">{{ description }}</p>
      </div>
      <ProviderStatePill :state="state" :test-id="stateTestId" />
    </header>

    <ApiErrorBanner
      v-if="error"
      class="settings-error"
      :data-test="errorTestId"
      :message="safeErrorMessage"
      :code="error.code"
      :retry-label="retryLabel ?? '重试'"
      :retrying="retrying ?? false"
      @retry="emit('retry')"
    />

    <section v-if="$slots.summary" class="settings-current-config" :data-test="summaryTestId">
      <slot name="summary" />
    </section>

    <slot />

    <footer v-if="$slots.footer" class="settings-card__footer">
      <slot name="footer" />
    </footer>
  </article>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此组件无自身私有样式。 */
</style>
