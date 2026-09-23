<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { useDialogReset } from '@/shared/ui/useDialogForm'
import UiDialogShell from '@/shared/ui/UiDialogShell.vue'
import type { GraphWorkspaceNodeView, GraphWorkspaceRouteView } from '@/shared/contracts/types'

/**
 * Fork（开新路线）与 Reanswer（重新回答）共用同一个对话框骨架：来源路线
 * 校验、归档恢复入口、路线命名输入完全一致，只有标题与按钮文案不同。
 * `mode` 决定文案与 data-test 前缀，交互语义保持与拆分前逐字一致。
 */
const props = defineProps<{
  open: boolean
  mode: 'fork' | 'reanswer'
  node: GraphWorkspaceNodeView | null
  sourceRoute?: GraphWorkspaceRouteView | null
  pending: boolean
  finalized?: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [label: string | null]
  'restore-source': [routeId: string]
}>()

const COPY = {
  fork: {
    title: '从这里开新路线',
    intro: '',
    placeholder: '分支路线',
    submitLabel: '创建分支',
    restoreLabel: '恢复此路线',
    restoreTestId: 'restore-base-route',
    zIndex: 40,
  },
  reanswer: {
    title: '重新回答',
    intro: '问题保持不变；新路线会从父级前缀开始，并等待你重新回答',
    placeholder: '重新回答路线',
    submitLabel: '创建重新回答路线',
    restoreLabel: '恢复来源路线',
    restoreTestId: 'restore-source',
    zIndex: 60,
  },
} as const

const copy = computed(() => COPY[props.mode])

const label = ref('')
const canSubmit = computed(() => {
  const sourceRoute = props.sourceRoute
  return !props.pending && props.node !== null && sourceRoute != null
    && (sourceRoute.lifecycleStatus === 'open' || sourceRoute.lifecycleStatus === 'superseded')
    && props.finalized === true
})

useDialogReset(toRef(props, 'open'), () => { label.value = '' })

function submit(): void {
  if (!canSubmit.value) return
  const trimmed = label.value.trim()
  emit('submit', trimmed.length > 0 ? trimmed : null)
}
</script>

<template>
  <UiDialogShell
    :open="open"
    :title="copy.title"
    :test-id="mode + '-dialog'"
    :z-index="copy.zIndex"
    @close="emit('close')"
  >
    <p v-if="copy.intro" class="muted">{{ copy.intro }}</p>
    <p v-if="!sourceRoute" class="info-line blocker" data-test="choose-reading-route">
      请先在该共享节点的“当前查看”控件中选择一条路线
    </p>
    <p v-else class="meta-text">来源：{{ sourceRoute.label ?? sourceRoute.id }}</p>
    <p v-if="sourceRoute && sourceRoute.lifecycleStatus === 'archived'" class="info-line blocker">请先恢复归档路线</p>
    <p v-if="sourceRoute && sourceRoute.lifecycleStatus === 'deleted'" class="info-line blocker">已删除路线不能作为操作来源</p>
    <p v-if="sourceRoute && !finalized" class="info-line blocker">该来源路线在此问题上还没有回答</p>
    <button
      v-if="sourceRoute && sourceRoute.lifecycleStatus === 'archived'"
      class="btn"
      type="button"
      :data-test="copy.restoreTestId"
      :disabled="pending"
      @click="emit('restore-source', sourceRoute.id)"
    >{{ copy.restoreLabel }}</button>
    <label class="secondary field-label">
      <span>路线名称（可选）</span>
      <input v-model="label" class="answer-input" :data-test="mode + '-label'" maxlength="255" :placeholder="copy.placeholder" />
    </label>
    <template #actions>
      <button class="btn btn-primary" type="button" :data-test="mode + '-submit'" :disabled="!canSubmit" @click="submit">{{ copy.submitLabel }}</button>
      <button class="btn" type="button" :data-test="mode + '-cancel'" :disabled="pending" @click="emit('close')">取消</button>
    </template>
  </UiDialogShell>
</template>

<style scoped>
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label input { display: block; margin-top: 4px; }
.info-line { font-size: 12px; }
.blocker { color: var(--color-warn); }
</style>
