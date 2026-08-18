<script setup lang="ts">
defineEmits<{
  'zoom-in': []
  'zoom-out': []
  'fit-view': []
  'auto-layout': []
  'show-all': []
}>()
</script>

<template>
  <div class="graph-toolbar" data-test="graph-toolbar">
    <button class="btn graph-toolbar__btn" data-test="zoom-in" title="放大" @click="$emit('zoom-in')">放大</button>
    <button class="btn graph-toolbar__btn" data-test="zoom-out" title="缩小" @click="$emit('zoom-out')">缩小</button>
    <button class="btn graph-toolbar__btn" data-test="fit-view" title="适应视图" @click="$emit('fit-view')">适应视图</button>
    <button class="btn graph-toolbar__btn" data-test="auto-layout" title="重新自动布局" @click="$emit('auto-layout')">重新自动布局</button>
    <button class="btn graph-toolbar__btn" data-test="show-all" title="显示全部路线" @click="$emit('show-all')">显示全部路线</button>
  </div>
</template>
