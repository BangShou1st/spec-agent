<script setup lang="ts">
defineProps<{ drafting: boolean; ideaPending?: boolean }>()
defineEmits<{ draft: []; 'add-idea': [] }>()
</script>

<template>
  <!-- The outer element is a stretched flex container: its box tracks the
       canvas, not the interactive region. Floating-layout obstacles measure
       the content wrapper (data-layout-role), so keep that role on the column
       the user actually reads and clicks. -->
  <div class="graph-start-placeholder" data-test="graph-start-placeholder">
    <div class="graph-start-placeholder__content" data-layout-role="start-placeholder">
      <h2 class="graph-start-placeholder__title">开始需求澄清</h2>
      <p class="graph-start-placeholder__hint">还没有任何内容。可以先起草问题，也可以先写下自己的想法。</p>
      <div class="graph-start-placeholder__actions">
        <button
          class="btn btn-primary"
          data-test="draft-question"
          :disabled="drafting"
          @click="$emit('draft')"
        >
          {{ drafting ? '正在起草…' : '起草第一个问题' }}
        </button>
        <button
          class="btn"
          data-test="add-idea"
          :disabled="ideaPending"
          title="创建一个空白草稿节点，不调用模型"
          @click="$emit('add-idea')"
        >
          {{ ideaPending ? '正在创建…' : '先写下想法' }}
        </button>
      </div>
    </div>
  </div>
</template>
