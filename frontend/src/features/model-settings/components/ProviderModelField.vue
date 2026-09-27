<!--
  文件名:ProviderModelField.vue
  用途:所有 Provider 卡片共用的模型选择字段:可选"仅免费"过滤、模型目录下拉,
       并覆盖目录的两种状态(为空;保存了 Provider 已不再提供的模型)。
       收口在一个组件里,避免三张卡片对同一种情形长出不同文案与规则。
-->
<script setup lang="ts">
/**
 * 所有 Provider 卡片共用的模型选择字段:可选的「仅免费」过滤、
 * 模型目录下拉,以及目录的两种状态(为空;或保存了该 Provider 已不再提供的模型)。
 *
 * 把这些收口在一个组件里,正是为了防止三张卡片对同一种情形
 * 慢慢长出不同的占位文案、不同的禁用规则和不同的警告文案。
 *
 * 「仅免费」开关原为独立组件 FreeOnlyToggle，但它只有本字段一个消费者、没有
 * 自身状态与独立测试，属无转发价值的包装，故内联在这里；渲染出的 DOM、class
 * 与 data-test 与内联前完全一致。
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
  /** 由调用方持有"仅免费"过滤状态时,渲染该复选框。 */
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
      <label
        v-if="freeOnly !== undefined"
        class="settings-free-toggle"
        :data-test="freeToggleId ?? 'provider-free-toggle'"
      >
        <input
          type="checkbox"
          :checked="freeOnly"
          @change="emit('update:freeOnly', ($event.target as HTMLInputElement).checked)"
        />
        <span>仅免费</span>
      </label>
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
