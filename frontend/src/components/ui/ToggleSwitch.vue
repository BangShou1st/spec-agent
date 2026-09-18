<script setup lang="ts">
/**
 * Lifecycle switch. One visual definition for every on/off control, so a
 * disabled-state toggle never grows a second look. Callers own the semantics:
 * the switch only reports a click, never mutates state itself.
 *
 * `data-test`, `title`, `aria-*` and other attributes fall through to the root
 * button, so each caller keeps its own test contract.
 */
withDefaults(defineProps<{
  checked: boolean
  disabled?: boolean
  onLabel?: string
  offLabel?: string
  /** data-test for the state text, when the caller's contract needs one. */
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
