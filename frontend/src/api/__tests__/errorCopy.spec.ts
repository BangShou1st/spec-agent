import { describe, expect, it } from 'vitest'
import {
  classifyModelFailure,
  productErrorMessage,
  requiresModelSettings,
} from '@/api/errorCopy'

describe('stable model error classifier and product copy', () => {
  it('maps provider unreachable to the Chinese connection copy', () => {
    expect(productErrorMessage('MODEL_PROVIDER_UNREACHABLE')).toBe(
      '无法连接到 OpenCode，请检查网络后重试。',
    )
    expect(classifyModelFailure('MODEL_PROVIDER_UNREACHABLE', 503)).toBe('retryable')
  })

  it('keeps network outcome unknown until canonical reconciliation', () => {
    expect(classifyModelFailure('NETWORK_ERROR', 0)).toBe('unknown')
    expect(classifyModelFailure('CONNECTION', 503)).toBe('retryable')
  })

  it('only marks the approved transient model failures retryable', () => {
    for (const code of [
      'RATE_LIMITED',
      'TIMEOUT',
      'SERVER_ERROR',
      'INVALID_RESPONSE',
      'EMPTY_CONTENT',
      'MODEL_CONTRACT_REJECTED',
    ]) {
      expect(classifyModelFailure(code, 500)).toBe('retryable')
    }
    expect(classifyModelFailure('VALIDATION_ERROR', 422)).toBe('none')
    expect(classifyModelFailure('NOT_CONFIGURED', 400)).toBe('none')
    expect(classifyModelFailure('AUTHENTICATION', 401)).toBe('none')
    expect(classifyModelFailure('INVALID_MODEL', 400)).toBe('none')
    expect(requiresModelSettings('INVALID_MODEL')).toBe(true)
  })
})
