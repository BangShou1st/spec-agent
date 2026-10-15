// 文件名:globalAssistantPresentation.spec.ts
// 用途:全局助手展示层单测:消息时间标签(今天只显时刻/昨天/同年/跨年)、
//       空态建议提问的标签与真实 prompt,以及错误码到用户文案的映射。
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

import { gaRetrievalStatusMessage, gaRetrievalStatusFixableInSettings } from '../globalAssistantPresentation'
it('explains disabled, offline embedding, storage and index readiness without assuming a provider', () => {
  // 旧聚合状态:通用指引,不再写死 Ollama/qwen3-embedding:0.6b。
  expect(gaRetrievalStatusMessage('OLLAMA_UNAVAILABLE')).toContain('向量服务')
  expect(gaRetrievalStatusMessage('OLLAMA_UNAVAILABLE')).not.toContain('qwen3')
  expect(gaRetrievalStatusMessage('STORE_UNAVAILABLE')).toContain('共享令牌')
  expect(gaRetrievalStatusMessage('HELP_INDEX_NOT_READY')).toContain('索引正在准备')
  expect(gaRetrievalStatusMessage('READY')).toBe('')
})
it('guides cloud credential loss to the retrieval settings, not Ollama installs', () => {
  const info = { provider: 'OPENAI_COMPATIBLE', model: 'text-embedding-v4' }
  const credential = gaRetrievalStatusMessage('EMBEDDING_CREDENTIAL_UNAVAILABLE', info)
  expect(credential).toContain('凭据缺失')
  expect(credential).toContain('知识检索设置')
  expect(credential).not.toContain('Ollama')
  expect(gaRetrievalStatusMessage('EMBEDDING_CONNECTION_FAILED', info)).toContain('无法连接向量服务（OPENAI_COMPATIBLE · text-embedding-v4）')
  expect(gaRetrievalStatusMessage('EMBEDDING_MODEL_NOT_FOUND', { provider: 'OLLAMA', model: 'nomic-embed-text' })).toContain('nomic-embed-text')
  expect(gaRetrievalStatusMessage('EMBEDDING_CONNECTION_FAILED')).not.toContain('（') // 原因未知时诚实省略身份
  expect(gaRetrievalStatusMessage('CONFIGURED')).toBe('') // 已配置≠持续健康,不冒充
})
it('only links to retrieval settings for failures the settings page can fix', () => {
  for (const status of ['OLLAMA_UNAVAILABLE', 'EMBEDDING_NOT_CONFIGURED', 'EMBEDDING_CREDENTIAL_UNAVAILABLE', 'EMBEDDING_CONNECTION_FAILED', 'EMBEDDING_MODEL_NOT_FOUND', 'EMBEDDING_TIMEOUT', 'EMBEDDING_UNAVAILABLE']) {
    expect(gaRetrievalStatusFixableInSettings(status)).toBe(true)
  }
  for (const status of ['PYTHON_UNAVAILABLE', 'STORE_UNAVAILABLE', 'HELP_INDEX_NOT_READY', 'RETRIEVAL_UNAVAILABLE', 'DISABLED', 'READY']) {
    expect(gaRetrievalStatusFixableInSettings(status)).toBe(false)
  }
})
