// 文件名:providerReachability.spec.ts
// 用途:上游可达性分类的单元测试:逐项锁定 200、429、401、403、5xx、
//       连接失败与超时的判定,以及「限流算可达、限流不算成功」这条边界。
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/http/client'
import {
  classifyProviderProbeFailure,
  classifyProviderReachability,
  isProviderReachable,
} from '@/shared/http/providerReachability'

describe('provider reachability classification', () => {
  it('treats HTTP 200 as reachable', () => {
    expect(classifyProviderReachability(200, '')).toBe('REACHABLE')
    expect(classifyProviderReachability(200, 'MODEL_PROVIDER_RATE_LIMITED')).toBe('REACHABLE')
    expect(isProviderReachable(classifyProviderReachability(200))).toBe(true)
  })

  it('treats HTTP 429 as reachable but rate limited', () => {
    expect(classifyProviderReachability(429, 'MODEL_PROVIDER_RATE_LIMITED')).toBe('RATE_LIMITED')
    expect(classifyProviderReachability(429, '')).toBe('RATE_LIMITED')
    expect(isProviderReachable('RATE_LIMITED')).toBe(true)
  })

  it('treats HTTP 401 as an authentication failure, never as reachable', () => {
    expect(classifyProviderReachability(401, 'MODEL_PROVIDER_AUTHENTICATION')).toBe('AUTHENTICATION_FAILED')
    expect(classifyProviderReachability(401, 'UNAUTHENTICATED')).toBe('SESSION_EXPIRED')
    expect(classifyProviderReachability(401, '')).toBe('AUTHENTICATION_FAILED')
    expect(isProviderReachable('AUTHENTICATION_FAILED')).toBe(false)
  })

  it('treats HTTP 403 as a permission failure, never as reachable', () => {
    expect(classifyProviderReachability(403, '')).toBe('PERMISSION_DENIED')
    expect(isProviderReachable('PERMISSION_DENIED')).toBe(false)
  })

  it('treats 5xx as a failure and never as reachable', () => {
    expect(classifyProviderReachability(500, 'MODEL_PROVIDER_ERROR')).toBe('FAILED')
    expect(classifyProviderReachability(502, '')).toBe('FAILED')
    expect(classifyProviderReachability(503, 'MODEL_PROVIDER_AUTHENTICATION')).toBe('AUTHENTICATION_FAILED')
    expect(isProviderReachable('FAILED')).toBe(false)
  })

  it('treats a connection failure as unreachable', () => {
    expect(classifyProviderReachability(0, 'NETWORK_ERROR')).toBe('UNREACHABLE')
    expect(classifyProviderReachability(502, 'MODEL_PROVIDER_UNREACHABLE')).toBe('UNREACHABLE')
    expect(isProviderReachable('UNREACHABLE')).toBe(false)
  })

  it('treats a timeout as a timeout and never as reachable', () => {
    expect(classifyProviderReachability(504, 'MODEL_PROVIDER_TIMEOUT')).toBe('TIMEOUT')
    expect(classifyProviderReachability(504, '')).toBe('TIMEOUT')
    expect(isProviderReachable('TIMEOUT')).toBe(false)
  })
})

describe('provider probe failure folding', () => {
  it('reports a rate limited upstream as reachable with the rate limited state kept', () => {
    const failure = classifyProviderProbeFailure(
      new ApiError('The model provider is temporarily rate limited', 'MODEL_PROVIDER_RATE_LIMITED', 429),
    )
    expect(failure.reachable).toBe(true)
    expect(failure.rateLimited).toBe(true)
    expect(failure.reachability).toBe('RATE_LIMITED')
    expect(failure.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
    expect(failure.message).toContain('上游可达')
  })

  it('reports an unreachable upstream as not reachable', () => {
    const failure = classifyProviderProbeFailure(
      new ApiError('The model provider could not be reached', 'MODEL_PROVIDER_UNREACHABLE', 502),
    )
    expect(failure.reachable).toBe(false)
    expect(failure.rateLimited).toBe(false)
    expect(failure.message).toContain('无法连接')
  })

  it('reports an authentication failure as not reachable and keeps the backend code', () => {
    const failure = classifyProviderProbeFailure(
      new ApiError('The model provider rejected the request configuration', 'MODEL_PROVIDER_AUTHENTICATION', 503),
    )
    expect(failure.reachable).toBe(false)
    expect(failure.code).toBe('MODEL_PROVIDER_AUTHENTICATION')
    expect(failure.message).toContain('认证')
  })

  it('never turns an unknown failure into a reachable upstream', () => {
    const failure = classifyProviderProbeFailure(
      new ApiError('boom', 'MODEL_PROVIDER_REJECTED', 502),
    )
    expect(failure.reachable).toBe(false)
    expect(failure.rateLimited).toBe(false)
  })

  it('never turns a non-API failure into a reachable upstream', () => {
    const failure = classifyProviderProbeFailure(new Error('boom'))
    expect(failure.reachable).toBe(false)
    expect(failure.rateLimited).toBe(false)
  })
})
