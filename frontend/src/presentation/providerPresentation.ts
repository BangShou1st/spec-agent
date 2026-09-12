import type { CustomApiFormat, ModelProvider } from '@/api/modelProviders'

/** Single place for Custom API format presentation. No apiFormat branching elsewhere. */
export const CUSTOM_FORMAT_OPTIONS: Array<{ value: CustomApiFormat; label: string }> = [
  { value: 'CHAT_COMPLETIONS', label: 'Chat Completions (/chat/completions)' },
  { value: 'RESPONSES', label: 'Responses (/responses)' },
]

// V1 product surface hides Anthropic Messages: it cannot serve the GA
// production JSON_OBJECT contract and is not activatable. The enum, adapter
// groundwork and endpoint suffix below stay for a future V1.1.
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

/** Deterministic preview mirroring backend canonical logic (presentation only). */
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

export function openRouterState(configured: boolean, validated: boolean, active: boolean, failed: boolean): ProviderState {
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
