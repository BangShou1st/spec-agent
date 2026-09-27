<!--
  文件名:ProviderSummaryItem.vue
  用途:ProviderCard 当前配置摘要中的单行"标签/值"项;抽成共享组件,
       让所有 Provider 的摘要渲染一致(含省略号规则与缺省占位符)。
-->
<script setup lang="ts">
/**
 * ProviderCard 当前配置摘要中的一行"标签/值"。
 * 抽成共享组件让所有 Provider 的摘要项渲染完全一致,
 * 包括省略号规则与缺省值的占位符。
 */
withDefaults(defineProps<{
  label: string
  value: string | null | undefined
  testId?: string
  /** 较长的单行值(如 Base URL、模型 id)不允许把网格撑到换行。 */
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
