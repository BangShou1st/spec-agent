<!--
  文件名:GraphStartPlaceholder.vue
  用途:空项目的画布起始占位:提供"起草第一个问题"与"先写下想法"两个入口。
-->
<script setup lang="ts">
defineProps<{ drafting: boolean; ideaPending?: boolean }>()
defineEmits<{ draft: []; 'add-idea': [] }>()
</script>

<template>
  <!-- 外层元素是一个拉伸的 flex 容器:它的盒子跟随画布,而不是交互区域。
       浮动布局的障碍物测量的是内容包装层(data-layout-role),所以这个
       role 要放在用户真正阅读与点击的那一列上。 -->
  <div class="graph-start-placeholder" data-test="graph-start-placeholder">
    <div class="graph-start-placeholder__content" data-layout-role="start-placeholder">
      <h2 class="graph-start-placeholder__title">开始需求澄清</h2>
      <p class="graph-start-placeholder__hint">还没有任何内容。可以先起草问题，也可以先写下自己的想法</p>
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
