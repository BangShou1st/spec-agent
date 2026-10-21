// 文件名:providerProbeReachability.spec.ts
// 用途:Provider「连通性/可达性探测」的语义单测:200 与 429 都算上游可达,
//       429 另外保留 RATE_LIMITED 状态;401/403、连接失败与超时都算失败;
//       凭据有效性(validate)绝不因为 429 就被认定为已验证。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/shared/http/client'
import {
  discoverCustom,
  getCustomStatus,
  validateCustom,
} from '@/features/model-settings/api/modelProviders'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import {
  listOpenRouterModels,
  probeOpenRouter,
  validateOpenRouter,
} from '@/features/model-settings/api/modelProviders'
import { useOpenRouterStore } from '@/features/model-settings/state/openRouterStore'

vi.mock('@/features/model-settings/api/modelProviders', () => ({
  discoverCustom: vi.fn(),
  getCustomStatus: vi.fn(),
  validateCustom: vi.fn(),
  listOpenRouterModels: vi.fn(),
  probeOpenRouter: vi.fn(),
  validateOpenRouter: vi.fn(),
}))

const mockedDiscover = vi.mocked(discoverCustom)
const mockedCustomStatus = vi.mocked(getCustomStatus)
const mockedValidateCustom = vi.mocked(validateCustom)
const mockedList = vi.mocked(listOpenRouterModels)
const mockedProbe = vi.mocked(probeOpenRouter)
const mockedValidate = vi.mocked(validateOpenRouter)

const RATE_LIMITED = new ApiError(
  'The model provider is temporarily rate limited',
  'MODEL_PROVIDER_RATE_LIMITED',
  429,
)
const AUTH_FAILED = new ApiError(
  'The model provider rejected the request configuration',
  'MODEL_PROVIDER_AUTHENTICATION',
  503,
)
const UNREACHABLE = new ApiError(
  'The model provider could not be reached',
  'MODEL_PROVIDER_UNREACHABLE',
  502,
)
const TIMEOUT = new ApiError(
  'The model provider did not respond in time',
  'MODEL_PROVIDER_TIMEOUT',
  504,
)

describe('provider probe reachability semantics', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedCustomStatus.mockResolvedValue({
      configured: false,
      apiFormat: 'CHAT_COMPLETIONS',
      baseUrl: '',
      endpointPreview: null,
      hasKey: false,
      maskedKey: null,
      selectedModel: '',
      manualModel: false,
      displayName: null,
      configRevision: 0,
      validated: false,
    })
    mockedList.mockResolvedValue({ allModels: [], freeModels: [] })
    mockedValidateCustom.mockResolvedValue({
      configured: true,
      apiFormat: 'CHAT_COMPLETIONS',
      baseUrl: 'https://gateway.example/v1',
      endpointPreview: 'https://gateway.example/v1/chat/completions',
      hasKey: true,
      maskedKey: '••••1234',
      selectedModel: 'custom-model-a',
      manualModel: false,
      displayName: 'Local Gateway',
      configRevision: 1,
      validated: true,
    })
  })

  it('treats a rate limited OpenRouter probe as a reachable upstream and keeps the rate limited state', async () => {
    mockedProbe.mockRejectedValue(RATE_LIMITED)
    const store = useOpenRouterStore()
    store.configured = true

    await expect(store.probe('key')).resolves.toEqual([])

    // 上游可达:绝不是「测试失败」。
    expect(store.failed).toBe(false)
    expect(store.rateLimited).toBe(true)
    // 限流状态与错误信息都保留下来。
    expect(store.error?.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
    expect(store.error?.message).toContain('上游可达')
  })

  it('treats an authentication failure as a failed probe', async () => {
    mockedProbe.mockRejectedValue(AUTH_FAILED)
    const store = useOpenRouterStore()
    store.configured = true

    await expect(store.probe('key')).resolves.toEqual([])

    expect(store.failed).toBe(true)
    expect(store.rateLimited).toBe(false)
    expect(store.error?.code).toBe('MODEL_PROVIDER_AUTHENTICATION')
  })

  it('treats an unreachable upstream as a failed probe', async () => {
    mockedProbe.mockRejectedValue(UNREACHABLE)
    const store = useOpenRouterStore()
    store.configured = true

    await expect(store.probe('key')).resolves.toEqual([])

    expect(store.failed).toBe(true)
    expect(store.rateLimited).toBe(false)
  })

  it('treats a timeout as a failed probe', async () => {
    mockedProbe.mockRejectedValue(TIMEOUT)
    const store = useOpenRouterStore()
    store.configured = true

    await expect(store.probe('key')).resolves.toEqual([])

    expect(store.failed).toBe(true)
    expect(store.rateLimited).toBe(false)
  })

  it('treats a rate limited model catalog refresh as a reachable upstream', async () => {
    mockedList.mockRejectedValue(RATE_LIMITED)
    const store = useOpenRouterStore()
    store.configured = true

    await store.refreshModels()

    expect(store.failed).toBe(false)
    expect(store.rateLimited).toBe(true)
    expect(store.error?.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
  })

  it('treats a rate limited custom discover as a reachable upstream', async () => {
    mockedDiscover.mockRejectedValue(RATE_LIMITED)
    const store = useCustomProviderStore()
    store.configured = true
    store.baseUrl = 'https://gateway.example/v1'

    await store.discover('key')

    expect(store.failed).toBe(false)
    expect(store.rateLimited).toBe(true)
    expect(store.error?.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
    // 限流绝不降级为手动填写模型。
    expect(store.manualModel).toBe(false)
  })

  it('never treats a rate limited credential validation as a validated provider', async () => {
    mockedValidateCustom.mockRejectedValue(RATE_LIMITED)
    const store = useCustomProviderStore()
    store.configured = true
    store.validated = true

    store.rateLimited = true
    await expect(store.validate()).resolves.toBe(false)
    expect(store.rateLimited).toBe(false)

    // 429 不单独证明凭证有效:验证必须失败。
    expect(store.validated).toBe(false)
    expect(store.failed).toBe(true)
  })

  it('never treats a rate limited OpenRouter validation as a validated provider', async () => {
    mockedValidate.mockRejectedValue(RATE_LIMITED)
    const store = useOpenRouterStore()
    store.configured = true
    store.validated = true

    store.rateLimited = true
    await expect(store.validate()).resolves.toBe(false)
    expect(store.rateLimited).toBe(false)

    expect(store.validated).toBe(false)
    expect(store.failed).toBe(true)
  })
})


// Zen and Kilo use different API modules from OpenRouter/Custom.
import { probeOpenCode, listOpenCodeModels, validateOpenCode } from '@/features/model-settings/api/modelSettings'
import { probeKilo, listKiloModels, validateKilo } from '@/features/model-settings/api/kiloSettings'
import { useModelSettingsStore } from '../modelSettingsStore'
import { useKiloSettingsStore } from '../kiloStore'
vi.mock('@/features/model-settings/api/modelSettings', () => ({
  probeOpenCode: vi.fn(), listOpenCodeModels: vi.fn(), validateOpenCode: vi.fn(),
}))
vi.mock('@/features/model-settings/api/kiloSettings', () => ({
  probeKilo: vi.fn(), listKiloModels: vi.fn(), validateKilo: vi.fn(),
}))

describe.each([
  ['Zen', useModelSettingsStore, probeOpenCode, listOpenCodeModels, validateOpenCode],
  ['Kilo', useKiloSettingsStore, probeKilo, listKiloModels, validateKilo],
] as const)('%s probe and actual validation boundary', (_name, useStore, probeApi, listApi, validateApi) => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })

  it('accepts successful discovery and preserves the returned catalog', async () => {
    vi.mocked(probeApi).mockResolvedValue({ allModels: ['fixture:free'], freeModels: ['fixture:free'] })
    const store = useStore()
    expect(await store.probe('fixture-only')).toEqual(['fixture:free'])
    expect(store.rateLimited).toBe(false)
    expect(store.error).toBeNull()
    expect(probeApi).toHaveBeenCalledTimes(1)
  })

  it.each([RATE_LIMITED, AUTH_FAILED, UNREACHABLE, TIMEOUT])('retains each failure category without retries: $code', async error => {
    vi.mocked(probeApi).mockRejectedValue(error)
    const store = useStore()
    await store.probe('fixture-only')
    expect(store.error?.code).toBe(error.code)
    expect(store.rateLimited).toBe(error === RATE_LIMITED)
    expect(probeApi).toHaveBeenCalledTimes(1)
  })

  it('keeps a rate-limited catalog reachable but rejects a subsequent actual validation', async () => {
    vi.mocked(listApi).mockRejectedValue(RATE_LIMITED)
    vi.mocked(validateApi).mockRejectedValue(RATE_LIMITED)
    const store = useStore()
    await store.refreshModels()
    expect(store.rateLimited).toBe(true)
    store.validated = true
    expect(await store.validate()).toBe(false)
    expect(store.validated).toBe(false)
    expect(store.rateLimited).toBe(false)
    expect(store.error?.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
    expect(validateApi).toHaveBeenCalledTimes(1)
  })
})
