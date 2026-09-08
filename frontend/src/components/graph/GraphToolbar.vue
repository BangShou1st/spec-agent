<script setup lang="ts">
import { useWorkspaceStore } from '@/stores/workspaceStore'

/**
 * Compact canvas toolbar: only frequent controls stay visible (+ 想法,
 * undo/redo, zoom, fit). Low-frequency actions (add resource, auto layout,
 * show all) live in a native overflow menu. Floating-window commands are
 * gone: Route and Inspector are fixed sidebar regions.
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
</script>

<template>
  <div class="graph-toolbar" data-test="graph-toolbar" data-layout-role="toolbar">
    <button class="btn graph-toolbar__btn graph-toolbar__btn--primary" data-test="add-idea" title="在当前路线添加一个草稿想法（不调用模型）" @click="$emit('add-idea')">+ 想法</button>

    <div class="graph-toolbar__group" aria-label="撤销与重做">
      <button class="btn graph-toolbar__icon-btn" data-test="undo" aria-label="撤销" title="撤销最近的图操作（保留历史，只做补偿）" :disabled="!workspace.undoRedo.canUndo || workspace.graphCommandPending" @click="$emit('undo')">↶</button>
      <button class="btn graph-toolbar__icon-btn" data-test="redo" aria-label="重做" title="重做最近撤销的操作（前置条件仍满足时）" :disabled="!workspace.undoRedo.canRedo || workspace.graphCommandPending" @click="$emit('redo')">↷</button>
    </div>

    <div class="graph-toolbar__group" aria-label="视图缩放">
      <button class="btn graph-toolbar__icon-btn" data-test="zoom-out" aria-label="缩小" title="缩小" @click="$emit('zoom-out')">−</button>
      <button class="btn graph-toolbar__icon-btn" data-test="zoom-in" aria-label="放大" title="放大" @click="$emit('zoom-in')">+</button>
      <button class="btn graph-toolbar__icon-btn" data-test="fit-view" aria-label="适应视图" title="适应视图" @click="$emit('fit-view')">⤢</button>
    </div>

    <details class="graph-toolbar__more" data-test="toolbar-more">
      <summary class="btn graph-toolbar__icon-btn" aria-label="更多图操作" title="更多图操作">···</summary>
      <div class="graph-toolbar__menu">
        <button class="graph-toolbar__menu-item" data-test="add-resource" title="添加资源节点（文本/链接/文件），AI 可读取有界摘录" @click="$emit('add-resource')">添加资源</button>
        <button class="graph-toolbar__menu-item" data-test="auto-layout" title="重新自动布局" @click="$emit('auto-layout')">重新自动布局</button>
        <button class="graph-toolbar__menu-item" data-test="show-all" title="显示全部路线" @click="$emit('show-all')">显示全部路线</button>
      </div>
    </details>
  </div>
</template>
