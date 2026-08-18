<script setup lang="ts">
/**
 * API 失败的错误横幅。只渲染安全的后端消息（或前端通用兜底）；原始响应体、
 * 堆栈、HTML 页面和 provider 载荷永不展示。可选的“重试”只重新触发用户的
 * 显式请求（仅 UI 重试）。
 */
defineProps<{
  message: string
  code?: string
  retryLabel?: string
  retrying?: boolean
}>()

const emit = defineEmits<{
  retry: []
}>()
</script>

<template>
  <div class="error-banner" role="alert">
    <div>
      <strong v-if="code">{{ code }}</strong>
      <span v-if="code"> — </span>
      {{ message }}
    </div>
    <button
      v-if="retryLabel"
      class="btn"
      type="button"
      :disabled="retrying"
      @click="emit('retry')"
    >
      {{ retrying ? '正在重试…' : retryLabel }}
    </button>
  </div>
</template>
