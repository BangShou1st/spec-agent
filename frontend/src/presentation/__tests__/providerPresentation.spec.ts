import { describe, expect, it } from 'vitest'
import {
  CUSTOM_FORMAT_OPTIONS,
  endpointPreview,
  formatLabel,
  openRouterState,
  stateLabel,
} from '@/presentation/providerPresentation'

describe('provider presentation', () => {
  it('renders only the two V1 format options', () => {
    const labels = CUSTOM_FORMAT_OPTIONS.map((o) => o.label)
    expect(labels).toContain('Chat Completions (/chat/completions)')
    expect(labels).toContain('Responses (/responses)')
    expect(labels).not.toContain('Anthropic Messages (/v1/messages)')
    expect(CUSTOM_FORMAT_OPTIONS).toHaveLength(2)
    // Stored Anthropic values still resolve a label for read-only display.
    expect(formatLabel('ANTHROPIC_MESSAGES')).toBe('Anthropic Messages (/v1/messages)')
  })

  it('previews canonical endpoints without double /v1', () => {
    expect(endpointPreview('http://localhost:11434/v1', 'CHAT_COMPLETIONS'))
      .toBe('http://localhost:11434/v1/chat/completions')
    expect(endpointPreview('https://gateway.example/v1/', 'RESPONSES'))
      .toBe('https://gateway.example/v1/responses')
    expect(endpointPreview('https://gateway.example/v1', 'ANTHROPIC_MESSAGES'))
      .toBe('https://gateway.example/v1/messages')
    expect(endpointPreview('', 'CHAT_COMPLETIONS')).toBeNull()
  })

  it('distinguishes configured, validated and active', () => {
    expect(openRouterState(false, false, false, false)).toBe('unconfigured')
    expect(openRouterState(true, false, false, false)).toBe('configured-unvalidated')
    expect(openRouterState(true, true, false, false)).toBe('valid-inactive')
    expect(openRouterState(true, true, true, false)).toBe('active')
    expect(openRouterState(true, false, false, true)).toBe('invalid')
    expect(stateLabel('configured-unvalidated')).not.toBe('Connected')
    expect(stateLabel('valid-inactive')).not.toBe('Disconnected')
  })
})
