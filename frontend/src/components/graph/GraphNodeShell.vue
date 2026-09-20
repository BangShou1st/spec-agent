<script lang="ts">
// Registered through the options `components` block (not a script-setup
// import) so the template resolves <Handle> by name; unit tests can then
// stub it, while the real app renders Vue Flow's Handle as usual.
import { Handle } from '@vue-flow/core'
export default { components: { Handle } }
</script>

<script setup lang="ts">
import { Position } from '@vue-flow/core'

/**
 * Shared node-card chassis for EVERY graph node type: the card root element
 * (drag / selection / dblclick semantics land here via attribute fallthrough),
 * the four-side adaptive edge anchors, the drag-handle header and the hover
 * action rail. Type-specific content stays in the per-kind card components
 * through slots — a new node kind reuses this chassis instead of copying it.
 *
 * Cards pass their identifying class / data-test / listeners as normal
 * attributes; Vue merges `class` onto the root and forwards the rest.
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
  /** Hides the whole action rail (e.g. while editing or answering inline). */
  showActions?: boolean
}>()
</script>

<template>
  <article class="graph-question-node" data-layout-role="graph-node">
    <!-- Adaptive edge anchors: one source + one target handle per side.
         Source handles accept manual drag-connections; target handles accept
         incoming ones. Invisible until node hover (style.css). -->
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
