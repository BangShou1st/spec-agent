<script setup lang="ts">
defineProps<{ drafting: boolean }>()
defineEmits<{ draft: [] }>()
</script>

<template>
  <div class="graph-start-placeholder" data-test="graph-start-placeholder">
    <h2 class="graph-start-placeholder__title">开始需求澄清</h2>
    <p class="graph-start-placeholder__hint">还没有生成任何问题。</p>
    <button
      class="btn btn-primary"
      data-test="draft-question"
      :disabled="drafting"
      @click="$emit('draft')"
    >
      {{ drafting ? '正在起草…' : '起草第一个问题' }}
    </button>
  </div>
</template>
