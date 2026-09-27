<!--
  文件名:NodeRecoveryBar.vue
  用途:节点底部的紧凑失败恢复栏。左侧简短原因(服务端 actionLabel/原因文案),
  右侧 32×32 的圆箭头恢复按钮。按钮常驻(不依赖 hover),具备 tooltip、
  aria-label 与键盘焦点;点击不触发画布拖拽(nodrag + 事件隔离)。同一节点
  多个未解决失败时列出"路线 + 动作"供用户明确选择,绝不用一个按钮猜目标。
  重试在途时按钮变为进度态并禁用。

  locateOnly 模式(顶部恢复汇总专用):只提供"查看/定位",组件内真实地
  不渲染任何重试/恢复控件——不是隐藏按钮,也不是只解除监听。真正的恢复
  动作只存在于对应失败位置(节点恢复栏/占位卡/规格面板/检查器)。
-->
<script setup lang="ts">
import { computed, ref } from 'vue'
import type { UnresolvedFailure } from '@/features/workspace/api/agentRuns'

export interface RecoveryItem {
  failure: UnresolvedFailure
  /** 失败绑定路线的展示名(由父级从 canonical 图解析)。 */
  routeLabel?: string
}

const props = defineProps<{
  items: RecoveryItem[]
  /** 该失败任务是否已有在途重试(乐观标记或服务端 retryRunId)。 */
  isRetrying: (failedRunId: string) => boolean
  /** 只读汇总模式:仅定位,不提供任何恢复/重试控件(顶部横幅)。 */
  locateOnly?: boolean
}>()

const emit = defineEmits<{
  retry: [failure: UnresolvedFailure]
  'go-settings': []
  locate: [failure: UnresolvedFailure]
}>()

const expanded = ref(false)
const single = computed(() => (props.items.length === 1 ? props.items[0] : null))
const pendingCount = computed(() => props.items.length)

/** 图标按钮的 aria-label / tooltip:动作名 + 路线,绝不含糊的"重试"。 */
function actionTitle(item: RecoveryItem): string {
  const route = item.routeLabel ? `（${item.routeLabel}）` : ''
  if (item.failure.availableAction === 'STALE') {
    return `查看变化${route}`
  }
  if (props.locateOnly) {
    return `查看${route}`
  }
  return `${item.failure.actionLabel}${route}`
}

function onIconClick(item: RecoveryItem): void {
  if (props.isRetrying(item.failure.runId)) return
  if (item.failure.availableAction === 'STALE') {
    emit('locate', item.failure)
    return
  }
  emit('retry', item.failure)
}

/** 只读模式下展开列表条目的简短原因:路线 + 简短原因,不含技术细节。 */
function itemSummary(item: RecoveryItem): string {
  const route = item.routeLabel ? item.routeLabel + ' · ' : ''
  const reason = item.failure.reasonSummary?.trim()
  return reason ? route + reason : route + item.failure.actionLabel
}
</script>

<template>
  <div
    v-if="items.length > 0"
    class="node-recovery nodrag"
    data-test="node-recovery-bar"
    @click.stop
    @keydown.stop
  >
    <!-- 只读汇总模式(顶部):单项只有"查看",多项展开后每项只有"定位" -->
    <template v-if="locateOnly">
      <div v-if="single" class="node-recovery__row">
        <span class="node-recovery__reason" data-test="pending-recovery-reason">
          ⚠ {{ itemSummary(single) }}
        </span>
        <button
          class="node-recovery__settings nodrag"
          type="button"
          data-test="pending-recovery-locate"
          :aria-label="actionTitle(single)"
          :title="actionTitle(single)"
          @click.stop="emit('locate', single.failure)"
        >
          查看
        </button>
      </div>
      <template v-else>
        <button
          class="node-recovery__summary nodrag"
          type="button"
          data-test="pending-recovery-toggle"
          :aria-expanded="expanded"
          @click.stop="expanded = !expanded"
        >
          ⚠ {{ pendingCount }} 项待处理 · 查看
        </button>
        <ul v-if="expanded" class="node-recovery__list" data-test="pending-recovery-list">
          <li v-for="item in items" :key="item.failure.runId" class="node-recovery__item">
            <span class="node-recovery__item-label" :data-test="'pending-recovery-item-' + item.failure.runId">
              {{ itemSummary(item) }}
            </span>
            <button
              class="node-recovery__settings node-recovery__settings--inline nodrag"
              type="button"
              :data-test="'pending-recovery-locate-' + item.failure.runId"
              :aria-label="actionTitle(item)"
              :title="actionTitle(item)"
              @click.stop="emit('locate', item.failure)"
            >
              定位
            </button>
          </li>
        </ul>
      </template>
    </template>

    <!-- 节点恢复模式:单一失败紧凑恢复栏 -->
    <template v-else-if="single">
      <div class="node-recovery__row">
        <span class="node-recovery__reason" data-test="node-recovery-reason">
          ⚠ {{ single.failure.reasonSummary ?? single.failure.actionLabel }}
        </span>
        <!-- 配置/凭据类失败:入口是模型设置 -->
        <button
          v-if="single.failure.availableAction === 'GO_TO_MODEL_SETTINGS'"
          class="node-recovery__settings nodrag"
          type="button"
          data-test="node-recovery-settings"
          @click.stop="emit('go-settings')"
        >
          前往模型设置
        </button>
        <!-- 结果未知/目标过期:定位查看 -->
        <button
          v-else-if="single.failure.availableAction === 'STALE'"
          class="node-recovery__icon nodrag"
          type="button"
          data-test="node-recovery-locate"
          :aria-label="actionTitle(single)"
          :title="actionTitle(single)"
          @click.stop="emit('locate', single.failure)"
        >
          ⋯
        </button>
        <!-- 常规恢复:32px 圆箭头,常驻 + 进度态 -->
        <button
          v-else
          class="node-recovery__icon nodrag"
          type="button"
          data-test="node-recovery-retry"
          :aria-label="actionTitle(single)"
          :title="actionTitle(single)"
          :disabled="isRetrying(single.failure.runId)"
          @click.stop="onIconClick(single)"
        >
          <span v-if="isRetrying(single.failure.runId)" class="node-recovery__spinner" aria-hidden="true">◌</span>
          <span v-else aria-hidden="true">↻</span>
        </button>
      </div>
    </template>

    <!-- 节点恢复模式:多个未解决失败,明确列出路线与动作供选择 -->
    <template v-else>
      <button
        class="node-recovery__summary nodrag"
        type="button"
        data-test="node-recovery-toggle"
        :aria-expanded="expanded"
        @click.stop="expanded = !expanded"
      >
        ⚠ {{ pendingCount }} 项待处理 · 查看
      </button>
      <ul v-if="expanded" class="node-recovery__list" data-test="node-recovery-list">
        <li v-for="item in items" :key="item.failure.runId" class="node-recovery__item">
          <span class="node-recovery__item-label">
            {{ item.routeLabel ? item.routeLabel + ' · ' : '' }}{{ item.failure.actionLabel }}
          </span>
          <button
            class="node-recovery__icon node-recovery__icon--inline nodrag"
            type="button"
            :data-test="'node-recovery-retry-' + item.failure.runId"
            :aria-label="actionTitle(item)"
            :title="actionTitle(item)"
            :disabled="isRetrying(item.failure.runId)"
            @click.stop="item.failure.availableAction === 'STALE' ? emit('locate', item.failure) : emit('retry', item.failure)"
          >
            {{ isRetrying(item.failure.runId) ? '◌' : '↻' }}
          </button>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.node-recovery {
  margin-top: 6px;
  padding: 6px 8px;
  border-top: 1px dashed var(--vscode-contrastBorder, rgba(128, 128, 128, 0.35));
  font-size: 11px;
  line-height: 1.4;
}
.node-recovery__row {
  display: flex;
  align-items: center;
  gap: 6px;
  justify-content: space-between;
}
.node-recovery__reason {
  color: var(--vscode-errorForeground, #b0563c);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.node-recovery__icon {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px solid var(--vscode-contrastBorder, rgba(128, 128, 128, 0.5));
  background: transparent;
  color: inherit;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.node-recovery__icon:disabled {
  cursor: default;
  opacity: 0.7;
}
.node-recovery__icon:focus-visible,
.node-recovery__settings:focus-visible,
.node-recovery__summary:focus-visible {
  outline: 2px solid var(--vscode-focusBorder, #4a9eff);
  outline-offset: 1px;
}
.node-recovery__spinner {
  display: inline-block;
  animation: node-recovery-spin 1.2s linear infinite;
}
@keyframes node-recovery-spin {
  to { transform: rotate(360deg); }
}
.node-recovery__settings {
  border: 1px solid var(--vscode-contrastBorder, rgba(128, 128, 128, 0.5));
  background: transparent;
  color: inherit;
  border-radius: 4px;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 11px;
  flex: 0 0 auto;
}
.node-recovery__settings--inline {
  padding: 2px 8px;
}
.node-recovery__settings:focus-visible {
  outline: 2px solid var(--vscode-focusBorder, #4a9eff);
  outline-offset: 1px;
}
.node-recovery__summary {
  border: none;
  background: transparent;
  color: var(--vscode-errorForeground, #b0563c);
  cursor: pointer;
  font-size: 11px;
  padding: 2px 0;
}
.node-recovery__list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.node-recovery__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.node-recovery__item-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-recovery__icon--inline {
  width: 28px;
  height: 28px;
  font-size: 14px;
}
</style>
