// 文件名:providerPresentation.ts
// 用途:Provider 展示层的共享映射:自定义 API 格式选项与标签、端点预览、
//       Provider 显示名、卡片状态的统一映射与状态文案。

import type { CustomApiFormat, ModelProvider } from '@/features/model-settings/api/modelProviders'

/** 自定义 API 格式展示的唯一出处;其他地方不允许按 apiFormat 分支。 */
export const CUSTOM_FORMAT_OPTIONS: Array<{ value: CustomApiFormat; label: string }> = [
  { value: 'CHAT_COMPLETIONS', label: 'Chat Completions (/chat/completions)' },
  { value: 'RESPONSES', label: 'Responses (/responses)' },
]

// V1 产品界面隐藏 Anthropic Messages:它无法满足 GA 生产环境的 JSON_OBJECT
// 契约,也不可激活。下面的枚举、适配器铺垫与端点后缀为将来的 V1.1 保留。
export function formatLabel(format: CustomApiFormat): string {
  const found = CUSTOM_FORMAT_OPTIONS.find((o) => o.value === format)
  if (found) {
    return found.label
  }
  if (format === 'ANTHROPIC_MESSAGES') {
    return 'Anthropic Messages (/v1/messages)'
  }
  return format
}

const SUFFIX: Record<CustomApiFormat, string> = {
  CHAT_COMPLETIONS: '/chat/completions',
  RESPONSES: '/responses',
  ANTHROPIC_MESSAGES: '/messages',
}

/** 确定性的端点预览,镜像后端规范化逻辑(仅展示层)。 */
export function endpointPreview(baseUrl: string, format: CustomApiFormat): string | null {
  const raw = (baseUrl ?? '').trim()
  if (!raw) {
    return null
  }
  let normalized = raw
  while (normalized.endsWith('/')) {
    normalized = normalized.slice(0, -1)
  }
  if (!normalized) {
    return null
  }
  return normalized + SUFFIX[format]
}

export function providerDisplayName(provider: ModelProvider): string {
  switch (provider) {
    case 'OPENCODE_ZEN':
      return 'OpenCode Zen'
    case 'OPENROUTER':
      return 'OpenRouter'
    case 'CUSTOM':
      return 'Custom'
  }
}

export type ProviderState =
  | 'unconfigured'
  | 'configured-unvalidated'
  | 'validating'
  | 'valid-inactive'
  | 'invalid'
  | 'active'

/**
 * 所有 Provider 卡片共用的单一状态映射。
 * `busy` 让卡片展示进行中状态,而不必新增第四种映射分支。
 */
export function providerCardState(
  configured: boolean,
  validated: boolean,
  active: boolean,
  failed: boolean,
  busy = false,
): ProviderState {
  if (busy) {
    return 'validating'
  }
  if (active && validated) {
    return 'active'
  }
  if (failed) {
    return 'invalid'
  }
  if (!configured) {
    return 'unconfigured'
  }
  if (validated) {
    return 'valid-inactive'
  }
  return 'configured-unvalidated'
}

export function openRouterState(configured: boolean, validated: boolean, active: boolean, failed: boolean): ProviderState {
  return providerCardState(configured, validated, active, failed)
}

export function stateLabel(state: ProviderState): string {
  switch (state) {
    case 'unconfigured':
      return '未配置'
    case 'configured-unvalidated':
      return '已配置但未验证'
    case 'validating':
      return '验证中'
    case 'valid-inactive':
      return '配置有效'
    case 'invalid':
      return '测试失败'
    case 'active':
      return '当前使用'
  }
}
