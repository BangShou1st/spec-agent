<!--
  文件名:ProviderStatePill.vue
  用途:Provider 状态胶囊组件:把共享的 ProviderState 映射为统一的视觉变体,
       是所有 Provider 卡片状态展示的单一来源。
-->
<script setup lang="ts">
import { computed } from 'vue'
import type { ProviderState } from '@/features/model-settings/presentation/providerPresentation'
import { stateLabel } from '@/features/model-settings/presentation/providerPresentation'

/**
 * 所有 Provider 卡片状态胶囊的单一来源。
 * 每张卡片把自己的领域状态映射到共享的 ProviderState 上,
 * 视觉变体因此绝不在卡片之间走样。
 */
const props = defineProps<{ state: ProviderState; testId?: string }>()
const label = computed(() => stateLabel(props.state))
const variantClass = computed(() => {
  switch (props.state) {
    case 'active':
      return 'settings-status--active'
    case 'valid-inactive':
      return 'settings-status--configured'
    case 'configured-unvalidated':
      return 'settings-status--warning'
    case 'invalid':
      return 'settings-status--error'
    default:
      return 'settings-status--empty'
  }
})
</script>

<template>
  <span class="settings-status" :class="variantClass" :data-test="testId ?? 'provider-state'">
    <span class="settings-status__dot" aria-hidden="true"></span>
    {{ label }}
  </span>
</template>
