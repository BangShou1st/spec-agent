<script setup lang="ts">
import FreeOnlyToggle from '@/components/providers/FreeOnlyToggle.vue'

/**
 * The model-selection field every provider card shares: an optional 仅免费
 * filter, the catalog select, and the two states a catalog can be in (empty,
 * or holding a saved selection the provider no longer exposes).
 *
 * Keeping this in one component is what stops the three cards from slowly
 * growing different placeholder text, different disabled rules and different
 * warning copy for the same situation.
 */
withDefaults(defineProps<{
  modelValue: string | null
  models: string[]
  disabled: boolean
  testId: string
  hint: string
  emptyText: string
  warningText?: string | null
  placeholder?: string
  /** 拉取中：此时列表为空只代表「还没到」，不代表「没有」。 */
  loading?: boolean
  /** Renders the 仅免费 checkbox when the caller owns that filter. */
  freeOnly?: boolean
  freeToggleId?: string
}>(), {
  warningText: null,
  placeholder: '请选择模型',
  loading: false,
  freeOnly: undefined,
  freeToggleId: undefined,
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | null): void
  (e: 'update:freeOnly', value: boolean): void
}>()

function onSelect(event: Event): void {
  emit('update:modelValue', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <label class="settings-field" :for="testId">
    <span class="provider-card__label-row">
      <span class="settings-field__label">Model</span>
      <FreeOnlyToggle
        v-if="freeOnly !== undefined"
        :checked="freeOnly"
        :test-id="freeToggleId"
        @change="emit('update:freeOnly', $event)"
      />
    </span>
    <span class="settings-field__hint">{{ hint }}</span>
    <select
      :id="testId"
      class="settings-control settings-model-select"
      :data-test="testId"
      :disabled="disabled"
      :value="modelValue ?? ''"
      @change="onSelect"
    >
      <option value="" disabled>{{ placeholder }}</option>
      <option v-for="model in models" :key="model" :value="model">{{ model }}</option>
    </select>
    <span v-if="models.length === 0 && !loading" class="settings-field__empty">{{ emptyText }}</span>
    <span
      v-if="warningText"
      class="settings-field__empty settings-field__warning"
      :data-test="`${testId}-unavailable`"
    >{{ warningText }}</span>
  </label>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此组件无自身私有样式。 */
</style>
