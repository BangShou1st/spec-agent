<script setup lang="ts">
import { computed } from 'vue'
import RichAssistantText from '@/components/global-assistant/RichAssistantText.vue'
import { resourceKindOf } from '@/presentation/resourceKind'

/**
 * 资源正文渲染：全项目唯一一处「文本内容怎么显示」的落点。
 *
 * - Markdown：复用全局助手同一套富文本渲染（marked + DOMPurify），标题、
 *   列表、代码块、引用都正确成型，而不是把源码原样铺出来；
 * - 代码 / 数据：等宽、保留空白，默认不折行以维持缩进结构；
 * - 纯文本：按正文排版，长行自动折行。
 *
 * 根元素只有一个，`data-test` 等属性可以安全地透传进来。
 */
const props = withDefaults(defineProps<{
  /** 资源路径或文件名，用来判定呈现方式。 */
  path?: string | null
  content: string
  /** 代码/数据是否折行；默认不折行。 */
  wrap?: boolean
}>(), {
  path: null,
  wrap: false,
})

const kind = computed(() => resourceKindOf(props.path))
const isMarkdown = computed(() => kind.value === 'markdown')
const isCode = computed(() => kind.value === 'code')
</script>

<template>
  <div class="resource-body">
    <RichAssistantText v-if="isMarkdown" :content="content" />
    <pre
      v-else
      class="resource-body__text"
      :class="{
        'resource-body__text--code': isCode,
        'resource-body__text--wrapped': isCode && wrap,
      }"
    >{{ content }}</pre>
  </div>
</template>

<style scoped>
.resource-body { font-size: 14.5px; line-height: 1.75; }
.resource-body__text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: inherit;
  font-size: var(--type-body);
  line-height: 1.7;
}
.resource-body__text--code {
  white-space: pre;
  word-break: normal;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
}
/* 折行只对代码/数据生效：缩进结构让位于长行可读性。 */
.resource-body__text--wrapped {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
