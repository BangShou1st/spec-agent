<script setup lang="ts">
import { computed, ref, useId } from 'vue'
const props = defineProps<{ accept?: string; disabled?: boolean; testId?: string; buttonLabel?: string; placeholder?: string }>()
const emit = defineEmits<{ (e: 'pick', file: File): void }>()
const inputId = useId()
const fileName = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
const displayName = computed(() => fileName.value || (props.placeholder ?? '尚未选择文件'))
function onChange(e: Event): void {
  const input = e.target as HTMLInputElement
  const f = input.files && input.files[0] ? input.files[0] : null
  if (!f) { fileName.value = ''; return }
  fileName.value = f.name
  emit('pick', f)
}
function openPicker(): void { inputEl.value?.click() }
function onKey(e: KeyboardEvent): void { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPicker() } }
</script>
<template>
  <div class="ui-filepicker" :data-test="props.testId ? props.testId + '-wrap' : 'ui-file-picker'">
    <input :id="inputId" ref="inputEl" class="ui-filepicker__input" type="file" :accept="props.accept" :disabled="props.disabled" :data-test="props.testId ?? 'ui-file-input'" @change="onChange" />
    <button type="button" class="btn btn-secondary ui-filepicker__btn" :disabled="props.disabled" @click="openPicker" @keydown="onKey">{{ props.buttonLabel ?? '选择文件' }}</button>
    <span class="ui-filepicker__name" :class="{ 'is-empty': !fileName }" aria-live="polite">{{ displayName }}</span>
  </div>
</template>
<style scoped>
.ui-filepicker { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ui-filepicker__input { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }
.ui-filepicker__input:focus-visible + .ui-filepicker__btn { box-shadow: var(--focus-ring); border-color: var(--color-focus); }
.ui-filepicker__btn { flex: none; }
.ui-filepicker__name { min-width: 0; flex: 1; font-size: 13px; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ui-filepicker__name.is-empty { color: var(--color-text-muted); }
</style>
