import { describe, expect, it } from 'vitest'
import {
  GA_EMPTY_SUGGESTIONS,
  gaErrorMessage,
  gaMessageTimeLabel,
} from '@/features/global-assistant/presentation/globalAssistantPresentation'

describe('global assistant message timestamps', () => {
  const now = new Date(2026, 8, 18, 17, 57) // 2026-09-18 17:57 local

  it('keeps only the clock for today', () => {
    expect(gaMessageTimeLabel(new Date(2026, 8, 18, 9, 5).toISOString(), now)).toBe('09:05')
    expect(gaMessageTimeLabel(new Date(2026, 8, 18, 23, 59).toISOString(), now)).toBe('23:59')
  })

  it('names yesterday explicitly', () => {
    expect(gaMessageTimeLabel(new Date(2026, 8, 17, 17, 53).toISOString(), now)).toBe('昨天 17:53')
  })

  it('carries the real date for anything older in the same year', () => {
    expect(gaMessageTimeLabel(new Date(2026, 8, 1, 8, 0).toISOString(), now)).toBe('9月1日 08:00')
    expect(gaMessageTimeLabel(new Date(2026, 0, 2, 20, 30).toISOString(), now)).toBe('1月2日 20:30')
  })

  it('adds the year when the message is not from this year', () => {
    expect(gaMessageTimeLabel(new Date(2025, 11, 31, 23, 0).toISOString(), now)).toBe('2025年12月31日 23:00')
  })

  it('returns nothing for missing or unparseable input', () => {
    expect(gaMessageTimeLabel(null, now)).toBe('')
    expect(gaMessageTimeLabel(undefined, now)).toBe('')
    expect(gaMessageTimeLabel('not-a-date', now)).toBe('')
  })
})

describe('empty state starters', () => {
  it('offers the three starters with a real prompt behind each label', () => {
    expect(GA_EMPTY_SUGGESTIONS.map((s) => s.label)).toEqual(['找项目', '读概要', '去页面'])
    for (const suggestion of GA_EMPTY_SUGGESTIONS) {
      expect(suggestion.prompt.trim().length).toBeGreaterThan(0)
    }
  })

  it('keeps every starter injectable as a distinct message', () => {
    const prompts = GA_EMPTY_SUGGESTIONS.map((s) => s.prompt)
    expect(new Set(prompts).size).toBe(prompts.length)
    for (const prompt of prompts) {
      expect(prompt.length).toBeLessThanOrEqual(200)
    }
  })
})

describe('ga error messages', () => {
  it('surfaces the backend reason for network tool failures', () => {
    const reason =
      'Git import failed: TransportException via local proxy 127.0.0.1:7897 '
      + '(Connection timed out). If this host needs a proxy, set spec.agent.skill.git.proxy=host:port'
    const message = gaErrorMessage('TOOL_EXECUTION_FAILED', reason)
    expect(message).toContain('网络连接失败')
    expect(message).toContain('TransportException')
    expect(message).toContain('127.0.0.1:7897')
  })

  it('recognizes the common socket-level failure names', () => {
    for (const reason of [
      'Git import failed: ConnectException (Connection refused)',
      'java.net.UnknownHostException: github.com',
      'Read timed out after 30000 ms',
      'SSLHandshakeException: handshake failed',
    ]) {
      expect(gaErrorMessage('TOOL_EXECUTION_FAILED', reason)).toContain('网络连接失败')
    }
  })

  it('keeps the friendly generic copy for non-network failures', () => {
    expect(gaErrorMessage('TOOL_EXECUTION_FAILED', 'boom')).toBe('工具执行失败，请稍后再试')
    expect(gaErrorMessage('TOOL_EXECUTION_FAILED', undefined)).toBe('工具执行失败，请稍后再试')
  })

  it('truncates very long network reasons but keeps the prefix visible', () => {
    const longReason = 'ConnectException ' + 'x'.repeat(300)
    const message = gaErrorMessage('TOOL_EXECUTION_FAILED', longReason)
    expect(message.startsWith('网络连接失败：')).toBe(true)
    expect(message.length).toBeLessThan(200)
  })
})
