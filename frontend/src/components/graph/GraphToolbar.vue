<script setup lang="ts">
import { useWorkspaceStore } from '@/stores/workspaceStore'

/**
 * Canvas toolbar. Zoom/layout buttons stay pure emits; undo/redo disabled
 * state mirrors the runtime operation log availability (read-only store
 * access — the command itself is emitted to the view layer like the others).
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
  routes: []
  inspector: []
  'reset-windows': []
}>()
</script>

<template>
  <div class="graph-toolbar" data-test="graph-toolbar" data-layout-role="toolbar">
    <button class="btn graph-toolbar__btn" data-test="add-idea" title="在当前路线添加一个草稿想法（不调用模型）" @click="$emit('add-idea')">+ 想法</button>
    <button class="btn graph-toolbar__btn" data-test="add-resource" title="添加资源节点（文本/链接/文件），AI 可读取有界摘录" @click="$emit('add-resource')">+ 资源</button>
    <button
      class="btn graph-toolbar__btn"
      data-test="undo"
      title="撤销最近的图操作（保留历史，只做补偿）"
      :disabled="!workspace.undoRedo.canUndo || workspace.graphCommandPending"
      @click="$emit('undo')"
    >撤销</button>
    <button
      class="btn graph-toolbar__btn"
      data-test="redo"
      title="重做最近撤销的操作（前置条件仍满足时）"
      :disabled="!workspace.undoRedo.canRedo || workspace.graphCommandPending"
      @click="$emit('redo')"
    >重做</button>
    <button class="btn graph-toolbar__btn" data-test="zoom-in" title="放大" @click="$emit('zoom-in')">放大</button>
    <button class="btn graph-toolbar__btn" data-test="zoom-out" title="缩小" @click="$emit('zoom-out')">缩小</button>
    <button class="btn graph-toolbar__btn" data-test="fit-view" title="适应视图" @click="$emit('fit-view')">适应视图</button>
    <button class="btn graph-toolbar__btn" data-test="auto-layout" title="重新自动布局" @click="$emit('auto-layout')">重新自动布局</button>
    <button class="btn graph-toolbar__btn" data-test="show-all" title="显示全部路线" @click="$emit('show-all')">显示全部路线</button>
    <button class="btn graph-toolbar__btn" data-test="open-routes" title="打开路线导航" @click="$emit('routes')">路线导航</button>
    <button class="btn graph-toolbar__btn" data-test="open-inspector" title="打开检查器" @click="$emit('inspector')">检查器</button>
    <button class="btn graph-toolbar__btn" data-test="reset-windows" title="重置窗口" @click="$emit('reset-windows')">重置窗口</button>
  </div>
</template>
