<!--
  文件名:GraphNodeShell.vue
  用途:所有画布节点类型共用的节点卡片外壳:卡片根元素(拖拽/选中/双击语义经属性透传落地)、四向自适应边锚点、拖拽头与悬停操作轨道;各类型内容通过插槽留在各自卡片组件中。
-->
<script lang="ts">
// 通过 options 式 `components` 块注册(而不是 script-setup 导入),让模板
// 按名字解析 <Handle>;单元测试因此可以 stub 它,真实应用照常渲染
// Vue Flow 的 Handle。
import { Handle } from '@vue-flow/core'
export default { components: { Handle } }
</script>

<script setup lang="ts">
import { Position } from '@vue-flow/core'


/**
 * 每种图节点类型共享的节点卡片外壳:卡片根元素(拖拽 / 选中 / 双击语义
 * 经属性透传落到这里)、四向自适应边锚点、拖拽头与悬停操作轨道。
 * 类型相关内容通过插槽留在各 kind 的卡片组件里——新节点 kind 复用这个
 * 外壳,而不是复制它。
 *
 * 各卡片把标识类 class / data-test / 监听器当普通属性传入;Vue 会把
 * `class` 合并到根元素并转发其余属性。
 */
const ANCHOR_SIDES: Position[] = [
  Position.Left,
  Position.Right,
  Position.Top,
  Position.Bottom,
]
const SOURCE_ANCHORS = ANCHOR_SIDES.map((side) => ({ id: 'source-' + side, position: side }))
const TARGET_ANCHORS = ANCHOR_SIDES.map((side) => ({ id: 'target-' + side, position: side }))

defineProps<{
  /** 隐藏整个操作轨道(例如编辑或行内作答时)。 */
  showActions?: boolean
}>()
</script>

<template>
  <article class="graph-question-node" data-layout-role="graph-node">
    <!-- 自适应边锚点:每侧一个 source + 一个 target handle。
         source handle 接受向外拖线;target handle 接受到来的连线。
         节点悬停前不可见(见 style.css)。 -->
    <Handle
      v-for="anchor in SOURCE_ANCHORS"
      :key="anchor.id"
      :id="anchor.id"
      type="source"
      :position="anchor.position"
      class="graph-question-node__handle graph-question-node__handle--source"
      :connectable="true"
      :connectable-start="true"
      :connectable-end="true"
      aria-hidden="true"
    />
    <Handle
      v-for="anchor in TARGET_ANCHORS"
      :key="anchor.id"
      :id="anchor.id"
      type="target"
      :position="anchor.position"
      class="graph-question-node__handle graph-question-node__handle--target"
      :connectable="true"
      :connectable-start="false"
      :connectable-end="true"
      aria-hidden="true"
    />

    <header class="graph-question-node__header" data-test="node-drag-handle" title="拖动标题栏移动节点">
      <slot name="header" />
    </header>

    <slot />

    <!-- 操作轨道：悬浮在节点右侧外缘竖排（left:100%），悬停或键盘聚焦
         节点时出现；内容由各类型卡片通过 #actions 提供。 -->
    <div
      v-if="showActions"
      class="graph-node-actions graph-node-actions--toolbar"
      tabindex="0"
      role="toolbar"
      aria-label="节点操作"
    >
      <slot name="actions" />
    </div>
  </article>
</template>
