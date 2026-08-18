<script setup lang="ts">
import { ref } from 'vue'

/**
 * 项目创建表单。客户端只做 UX 层面的空值校验；标题校验以后端为准。
 */
const props = defineProps<{
  creating: boolean
}>()

const emit = defineEmits<{
  create: [title: string]
}>()

const title = ref('')
const touched = ref(false)

const blank = (): boolean => title.value.trim().length === 0

function submit(): void {
  touched.value = true
  if (blank() || props.creating) {
    return
  }
  emit('create', title.value.trim())
  title.value = ''
  touched.value = false
}
</script>

<template>
  <form class="create-form" @submit.prevent="submit">
    <input
      v-model="title"
      type="text"
      maxlength="255"
      placeholder="项目标题"
      aria-label="Project title"
    />
    <button class="btn btn-primary" type="submit" :disabled="creating || blank()">
      {{ creating ? '正在创建…' : '创建项目' }}
    </button>
  </form>
  <p v-if="touched && blank()" class="muted">请输入项目标题以创建项目。</p>
</template>
