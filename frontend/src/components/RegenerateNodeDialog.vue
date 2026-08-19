<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphWorkspaceNodeView, GraphWorkspaceRouteView, RegenerateNodeRequest, RouteLifecycleStatus } from '@/api/types'

/**
 * 创建替代问题（确定性）对话框。打开时用所选历史节点的内容预填替代
 * 问题、目的与替代选项 label/impact——只复制用户可控内容。旧选项 id 永远
 * 不会被复制进请求；运行时生成新 id。不调用任何模型。
 */
const props = defineProps<{
  open: boolean
  node: GraphWorkspaceNodeView | null
  pending: boolean
  routes: GraphWorkspaceRouteView[]
}>()

const emit = defineEmits<{
  close: []
  submit: [payload: RegenerateNodeRequest]
}>()

interface EditableOption {
  label: string
  impact: string
}

const instruction = ref('')
const question = ref('')
const purpose = ref('')
const options = ref<EditableOption[]>([])
const sourceRouteId = ref<string | null>(null)

function lifecycleLabel(status: RouteLifecycleStatus): string {
  return { open: '开放', superseded: '已替代', archived: '已归档', deleted: '已删除' }[status]
}

const sourceRoutes = computed(() => props.node
  ? props.routes.filter((route) => route.lineageNodeIds.includes(props.node!.id))
  : [])

watch(
  () => props.open,
  (open) => {
    if (open && props.node) {
      instruction.value = ''
      question.value = props.node.question
      purpose.value = props.node.purpose ?? ''
      options.value = props.node.options.map((option) => ({
        label: option.label,
        impact: option.impact ?? '',
      }))
      sourceRouteId.value = null
      const openSources = sourceRoutes.value.filter((route) => route.lifecycleStatus === 'open')
      if (openSources.length === 1) sourceRouteId.value = openSources[0].id
    }
  },
  { immediate: true },
)

const isRootNode = computed<boolean>(() => props.node?.parentNodeId === null)

const questionError = computed<string | null>(() =>
  question.value.trim().length === 0 ? '替代问题不能为空。' : null,
)

const optionErrors = computed<boolean[]>(
  () => options.value.map((option) => option.label.trim().length === 0),
)

const canSubmit = computed<boolean>(() => {
  if (props.pending || isRootNode.value || questionError.value !== null) {
    return false
  }
  if (sourceRoutes.value.length === 0
    || sourceRouteId.value === null
    || sourceRoutes.value.find((route) => route.id === sourceRouteId.value)?.lifecycleStatus !== 'open') return false
  return !optionErrors.value.some(Boolean)
})

function addOption(): void {
  options.value.push({ label: '', impact: '' })
}

function removeOption(index: number): void {
  options.value.splice(index, 1)
}

function submit(): void {
  if (!canSubmit.value || !sourceRouteId.value) {
    return
  }
  const payload: RegenerateNodeRequest = {
    sourceRouteId: sourceRouteId.value,
    instruction: instruction.value.trim().length > 0 ? instruction.value.trim() : null,
    replacementQuestion: question.value.trim(),
    replacementPurpose: purpose.value.trim().length > 0 ? purpose.value.trim() : null,
    replacementOptions: options.value.map((option) => ({
      label: option.label.trim(),
      impact: option.impact.trim().length > 0 ? option.impact.trim() : null,
    })),
  }
  emit('submit', payload)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="regenerate-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="替换这个问题">
      <h3 style="margin-top: 0">替换这个问题</h3>
      <p class="muted" style="margin-top: 0">
        创建替代节点和新的当前路线。旧路线变为已替代。不调用任何模型——以下内容全部是确定性的。
      </p>

      <p v-if="isRootNode" class="info-line regenerate-blocker" data-test="regenerate-root-blocker">
        根问题暂不支持替换。
      </p>

      <fieldset class="regenerate-source-routes">
        <legend class="secondary">明确选择来源路线</legend>
        <label v-for="route in sourceRoutes" :key="route.id" class="fork-base-route">
          <input v-model="sourceRouteId" type="radio" name="regenerate-source-route" :value="route.id" data-test="regenerate-source-route" />
          <span>{{ route.label ?? route.id.slice(0, 8) }}</span>
          <span class="badge" :class="`badge-${route.lifecycleStatus}`">{{ lifecycleLabel(route.lifecycleStatus) }}</span>
        </label>
      </fieldset>

      <label class="field-label secondary">
        <span>说明（可选，要改什么）</span>
        <textarea
          v-model="instruction"
          class="answer-input"
          data-test="regenerate-instruction"
          maxlength="2000"
          placeholder="例如：把范围问题改得更窄"
        ></textarea>
      </label>

      <label class="field-label secondary">
        <span>替代问题</span>
        <textarea
          v-model="question"
          class="answer-input"
          data-test="regenerate-question"
          maxlength="4000"
        ></textarea>
        <span v-if="questionError" class="field-error" data-test="regenerate-question-error">
          {{ questionError }}
        </span>
      </label>

      <label class="field-label secondary">
        <span>替代目的（可选）</span>
        <textarea
          v-model="purpose"
          class="answer-input"
          data-test="regenerate-purpose"
          maxlength="4000"
        ></textarea>
      </label>

      <fieldset style="border: 0; padding: 0; margin: 12px 0 0">
        <legend class="secondary" style="font-size: 13px">替代选项（可选）</legend>
        <div
          v-for="(option, index) in options"
          :key="index"
          class="replacement-option"
          data-test="replacement-option-row"
        >
          <input
            v-model="option.label"
            class="option-input"
            data-test="replacement-option-label"
            maxlength="500"
            placeholder="选项内容"
          />
          <input
            v-model="option.impact"
            class="option-input"
            data-test="replacement-option-impact"
            maxlength="2000"
            placeholder="影响（可选）"
          />
          <button class="btn btn-small" type="button" data-test="replacement-option-remove" @click="removeOption(index)">
            移除
          </button>
          <span v-if="optionErrors[index]" class="field-error" data-test="replacement-option-error">
            选项内容不能为空。
          </span>
        </div>
        <button class="btn btn-small" type="button" data-test="replacement-option-add" @click="addOption">
          + 添加选项
        </button>
      </fieldset>

      <div style="display: flex; gap: 8px; margin-top: 16px">
        <button
          class="btn btn-primary"
          type="button"
          data-test="regenerate-submit"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ pending ? '正在创建替代问题…' : '创建替代问题' }}
        </button>
        <button class="btn" type="button" data-test="regenerate-cancel" :disabled="pending" @click="emit('close')">
          取消
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 20, 30, 0.45);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 48px 16px;
  z-index: 40;
  overflow-y: auto;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: 18px;
  width: 100%;
  max-width: 640px;
  box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25);
}

.field-label {
  display: block;
  margin-top: 10px;
  font-size: 13px;
}

.field-label .answer-input {
  display: block;
  min-height: 72px;
  margin-top: 4px;
}

.field-error {
  display: block;
  color: var(--color-danger);
  font-size: 12px;
  margin-top: 2px;
}

.info-line {
  font-size: 12px;
}

.regenerate-blocker {
  color: var(--color-warn);
  margin: 0 0 6px;
}

.regenerate-source-routes {
  border: 0;
  padding: 0;
  margin: 0 0 10px;
}

.fork-base-route {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 4px;
}

.replacement-option {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.option-input {
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  flex: 1;
  min-width: 160px;
}

.btn-small {
  padding: 3px 10px;
  font-size: 12px;
}
</style>
