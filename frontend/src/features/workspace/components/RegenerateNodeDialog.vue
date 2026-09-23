<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { useDialogReset } from '@/shared/ui/useDialogForm'
import UiDialogShell from '@/shared/ui/UiDialogShell.vue'
import type { GraphWorkspaceNodeView, RegenerateNodeRequest } from '@/shared/contracts/types'

const props = defineProps<{
  open: boolean
  node: GraphWorkspaceNodeView | null
  sourceRouteId?: string | null
  pending: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [payload: RegenerateNodeRequest]
}>()

const instruction = ref('')
const isRootNode = computed(() => props.node?.parentNodeId === null)
const canSubmit = computed(() => !props.pending && !isRootNode.value
  && props.sourceRouteId !== null && instruction.value.trim().length > 0)

useDialogReset(toRef(props, 'open'), () => { instruction.value = '' })

function submit(): void {
  if (!canSubmit.value || !props.sourceRouteId) return
  emit('submit', { sourceRouteId: props.sourceRouteId, instruction: instruction.value.trim() })
}
</script>

<template>
  <UiDialogShell :open="open" title="换一个问题" test-id="regenerate-dialog" :z-index="60" @close="emit('close')">
    <p class="muted">
      你接下来更想澄清哪个方面？也可以直接说说你目前最关心的需求
    </p>
    <p v-if="node" class="meta-text">当前问题：{{ node.question }}</p>
    <p v-if="isRootNode" class="info-line regenerate-blocker" data-test="regenerate-root-blocker">
      根问题暂不支持替换
    </p>
    <p v-else-if="!sourceRouteId" class="info-line regenerate-blocker" data-test="regenerate-source-blocker">
      请先在该共享节点的“当前查看”控件中选择一条路线
    </p>

    <label class="field-label secondary">
      <span>方向</span>
      <textarea
        v-model="instruction"
        class="answer-input replace-question-direction"
        data-test="regenerate-instruction"
        maxlength="2000"
        placeholder="说说你目前最关心的需求"
      ></textarea>
    </label>

    <template #actions>
      <button class="btn btn-primary" type="button" data-test="regenerate-submit" :disabled="!canSubmit" @click="submit">
        {{ pending ? '正在生成…' : '生成新问题' }}
      </button>
      <button class="btn" type="button" data-test="regenerate-cancel" :disabled="pending" @click="emit('close')">
        取消
      </button>
    </template>
  </UiDialogShell>
</template>

<style scoped>
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label .answer-input { display: block; min-height: 100px; margin-top: 4px; }
.info-line { font-size: 12px; }
.regenerate-blocker { color: var(--color-warn); }
</style>
