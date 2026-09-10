<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

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
  <form class="create-form projects-create__form" @submit.prevent="submit">
    <label class="projects-create__label" for="project-create-input">新建项目</label>
    <div class="projects-create__row">
      <input
        id="project-create-input"
        v-model="title"
        type="text"
        maxlength="255"
        placeholder="例如：AI 邮件助手"
        aria-label="Project title"
        :aria-invalid="touched && blank() ? 'true' : undefined"
        :disabled="creating"
      />
      <button class="btn btn-primary" type="submit" :disabled="creating || blank()">
        <AppIcon v-if="!creating" name="plus" />
        <span>{{ creating ? '正在创建…' : '创建项目' }}</span>
      </button>
    </div>
    <p class="form-helper">输入标题后回车即可创建，创建后直接进入工作区。</p>
    <p v-if="touched && blank()" class="form-error" role="alert">请输入项目标题以创建项目。</p>
  </form>
</template>
