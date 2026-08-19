<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphWorkspaceNodeView, RegenerateNodeRequest } from '@/api/types'

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

watch(() => props.open, (open) => {
  if (open) instruction.value = ''
}, { immediate: true })

function submit(): void {
  if (!canSubmit.value || !props.sourceRouteId) return
  emit('submit', { sourceRouteId: props.sourceRouteId, instruction: instruction.value.trim() })
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="regenerate-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="换一个问题">
      <h3 style="margin-top: 0">换一个问题</h3>
      <p class="muted" style="margin-top: 0">
        你接下来更想澄清哪个方面？也可以直接说说你目前最关心的需求。
      </p>
      <p v-if="node" class="meta-text">当前问题：{{ node.question }}</p>
      <p v-if="isRootNode" class="info-line regenerate-blocker" data-test="regenerate-root-blocker">
        根问题暂不支持替换。
      </p>
      <p v-else-if="!sourceRouteId" class="info-line regenerate-blocker" data-test="regenerate-source-blocker">
        共享节点需要先在“当前查看”中选择一条路线。
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

      <div class="dialog-actions">
        <button class="btn btn-primary" type="button" data-test="regenerate-submit" :disabled="!canSubmit" @click="submit">
          {{ pending ? '正在生成…' : '生成新问题' }}
        </button>
        <button class="btn" type="button" data-test="regenerate-cancel" :disabled="pending" @click="emit('close')">
          取消
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; background: rgba(15, 20, 30, 0.45); display: flex; align-items: flex-start; justify-content: center; padding: 80px 16px; z-index: 60; }
.dialog { background: var(--color-surface); border-radius: var(--radius); padding: 18px; width: 100%; max-width: 520px; box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25); }
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label .answer-input { display: block; min-height: 100px; margin-top: 4px; }
.dialog-actions { display: flex; gap: 8px; margin-top: 16px; }
.info-line { font-size: 12px; }
.regenerate-blocker { color: var(--color-warn); }
</style>
