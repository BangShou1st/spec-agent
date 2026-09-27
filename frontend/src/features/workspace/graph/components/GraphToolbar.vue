<!--
  文件名:GraphToolbar.vue
  用途:画布左侧的紧凑工具栏:高频操作(加想法、撤销/重做、缩放、适应视图)常驻,低频操作(添加资源、重新自动布局、显示全部)收进原生溢出菜单。
-->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useWorkspaceStore } from '@/features/workspace/state/workspaceStore'
import AppIcon from '@/shared/ui/AppIcon.vue'

/**
 * 紧凑的画布工具栏:只有高频控件常驻(+ 想法、undo/redo、缩放、适应视图)。
 * 低频操作(添加资源、自动布局、显示全部)收进原生溢出菜单。浮动窗口命令
 * 已移除:路线与 Inspector 是固定的侧栏区域。
 *
 * 工具栏刻意保持窄条(竖条),因此"只看这条路线"的状态指示器放在画布上
 * (GraphCanvas),而不是这里。
 */
const workspace = useWorkspaceStore()

defineEmits<{
  'zoom-in': []
  'zoom-out': []
  'fit-view': []
  'auto-layout': []
  'show-all': []
  'add-idea': []
  'add-resource': []
  undo: []
  redo: []
}>()

// 原生 details/summary 不会因外部点击或 Esc 收起，菜单会一直挂在画布上。
const moreMenu = ref<HTMLDetailsElement | null>(null)

function closeMoreMenu(event: Event): void {
  if (!moreMenu.value?.open) return
  if (event instanceof KeyboardEvent) {
    // Esc 无论焦点在不在菜单内都应收起;其他按键不处理。
    if (event.key === 'Escape') moreMenu.value.open = false
    return
  }
  const target = event.target as Node | null
  if (target && moreMenu.value.contains(target)) return
  moreMenu.value.open = false
}

onMounted(() => {
  document.addEventListener('pointerdown', closeMoreMenu, true)
  document.addEventListener('keydown', closeMoreMenu)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeMoreMenu, true)
  document.removeEventListener('keydown', closeMoreMenu)
})
</script>

<template>
  <div class="graph-toolbar" data-test="graph-toolbar" data-layout-role="toolbar">
    <button class="btn graph-toolbar__btn graph-toolbar__btn--primary" data-test="add-idea" title="在当前路线添加一个草稿想法（不调用模型）" @click="$emit('add-idea')"><AppIcon name="plus" /><span>想法</span></button>

    <div class="graph-toolbar__group" aria-label="撤销与重做">
      <button class="btn graph-toolbar__icon-btn" data-test="undo" aria-label="撤销" title="撤销最近的图操作（保留历史，只做补偿）" :disabled="!workspace.undoRedo.canUndo || workspace.graphCommandPending" @click="$emit('undo')"><AppIcon name="undo" /></button>
      <button class="btn graph-toolbar__icon-btn" data-test="redo" aria-label="重做" title="重做最近撤销的操作（前置条件仍满足时）" :disabled="!workspace.undoRedo.canRedo || workspace.graphCommandPending" @click="$emit('redo')"><AppIcon name="redo" /></button>
    </div>

    <div class="graph-toolbar__group" aria-label="视图缩放">
      <button class="btn graph-toolbar__icon-btn" data-test="zoom-out" aria-label="缩小" title="缩小" @click="$emit('zoom-out')"><AppIcon name="zoom-out" /></button>
      <button class="btn graph-toolbar__icon-btn" data-test="zoom-in" aria-label="放大" title="放大" @click="$emit('zoom-in')"><AppIcon name="zoom-in" /></button>
      <button class="btn graph-toolbar__icon-btn" data-test="fit-view" aria-label="适应视图" title="适应视图" @click="$emit('fit-view')"><AppIcon name="fit" /></button>
    </div>

    <details ref="moreMenu" class="graph-toolbar__more" data-test="toolbar-more">
      <summary class="btn graph-toolbar__icon-btn" aria-label="更多图操作" title="更多图操作"><AppIcon name="more" /></summary>
      <div class="graph-toolbar__menu">
        <button class="graph-toolbar__menu-item" data-test="add-resource" title="添加资源节点（文本/链接/文件），AI 可读取有界摘录" @click="$emit('add-resource')">添加资源</button>
        <button class="graph-toolbar__menu-item" data-test="auto-layout" title="重新自动布局" @click="$emit('auto-layout')">重新自动布局</button>
        <button class="graph-toolbar__menu-item" data-test="show-all" title="显示全部路线" @click="$emit('show-all')">显示全部路线</button>
      </div>
    </details>
  </div>
</template>
