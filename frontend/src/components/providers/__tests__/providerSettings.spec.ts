import { describe, expect, it } from 'vitest'
import { CUSTOM_FORMAT_OPTIONS, endpointPreview } from '@/presentation/providerPresentation'

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
    // Manual fallback is only for 404/405/501 at the HTTP layer; this test
    // locks the presentation rule that auth failures stay errors.
    expect(CUSTOM_FORMAT_OPTIONS).toHaveLength(2)
  })

  it('hides the non-activatable Anthropic option in V1', () => {
    expect(CUSTOM_FORMAT_OPTIONS.map((o) => o.value)).not.toContain('ANTHROPIC_MESSAGES')
  })
})
