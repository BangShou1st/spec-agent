<!--
  文件名:ToggleSwitch.vue
  用途:生命周期开关组件:所有开/关控件的统一视觉定义,只上报点击事件、绝不自行改状态,语义由调用方持有。
-->
<script setup lang="ts">
/*
 * 生命周期开关。所有开/关控件共用一份视觉定义,禁用态的开关绝不会长出
 * 第二种样子。语义由调用方持有:开关只上报一次点击,绝不自己改状态。
 *
 * `data-test`、`title`、`aria-*` 等属性透传到根按钮,每个调用方保留自己
 * 的测试契约。
 */
withDefaults(defineProps<{
  checked: boolean
  disabled?: boolean
  onLabel?: string
  offLabel?: string
  /** 状态文本的 data-test,调用方契约需要时提供。 */
  statusTestId?: string
}>(), {
  disabled: false,
  onLabel: '已启用',
  offLabel: '已禁用',
  statusTestId: undefined,
})

const emit = defineEmits<{ (e: 'toggle'): void }>()
</script>

<template>
  <button
    type="button"
    class="toggle-switch"
    role="switch"
    :aria-checked="checked"
    :class="{ 'toggle-switch--on': checked }"
    :disabled="disabled"
    @click="emit('toggle')"
  >
    <span class="toggle-switch__track" aria-hidden="true"><span class="toggle-switch__thumb"></span></span>
    <span class="toggle-switch__label" :data-test="statusTestId">{{ checked ? onLabel : offLabel }}</span>
  </button>
</template>

<style scoped>
.toggle-switch { display: inline-flex; align-items: center; gap: 7px; padding: 4px 6px; background: none; border: none; border-radius: 999px; cursor: pointer; }
.toggle-switch:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.toggle-switch:disabled { opacity: 0.55; cursor: progress; }
.toggle-switch__track { position: relative; flex: none; width: 32px; height: 18px; border-radius: 999px; background: var(--color-border-strong); transition: background 140ms ease; }
.toggle-switch--on .toggle-switch__track { background: var(--color-success); }
.toggle-switch__thumb { position: absolute; top: 2px; left: 2px; width: 14px; height: 14px; border-radius: 50%; background: #fff; box-shadow: 0 1px 2px rgb(15 23 42 / 25%); transition: transform 140ms ease; }
.toggle-switch--on .toggle-switch__thumb { transform: translateX(14px); }
.toggle-switch__label { font-size: 12px; font-weight: 600; color: var(--color-text-secondary); white-space: nowrap; }
.toggle-switch--on .toggle-switch__label { color: var(--color-success); }
</style>
