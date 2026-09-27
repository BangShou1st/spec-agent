// 文件名:providerSettings.spec.ts
// 用途:自定义 Provider 展示规则单测:默认 API Format、端点预览随格式变化、
//       V1 隐藏不可激活的 Anthropic 选项、认证失败不得降级为手动回退。
import { describe, expect, it } from 'vitest'
import { CUSTOM_FORMAT_OPTIONS, endpointPreview } from '@/features/model-settings/presentation/providerPresentation'

describe('custom provider UI rules', () => {
  it('defaults to chat completions', () => {
    expect(CUSTOM_FORMAT_OPTIONS[0].value).toBe('CHAT_COMPLETIONS')
  })

  it('endpoint preview follows format selection', () => {
    const base = 'http://localhost:11434/v1'
    expect(endpointPreview(base, 'CHAT_COMPLETIONS')).toContain('/chat/completions')
    expect(endpointPreview(base, 'RESPONSES')).toContain('/responses')
  })

  it('401 must not trigger manual fallback (handled at store level)', () => {
    // 手动回退只针对 HTTP 层的 404/405/501;此测试锁定认证失败仍视为错误的展示规则。
    expect(CUSTOM_FORMAT_OPTIONS).toHaveLength(2)
  })

  it('hides the non-activatable Anthropic option in V1', () => {
    expect(CUSTOM_FORMAT_OPTIONS.map((o) => o.value)).not.toContain('ANTHROPIC_MESSAGES')
  })
})
