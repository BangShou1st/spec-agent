// 文件名:customOriginGuard.spec.ts
// 用途:自定义 Provider 凭据复用来源守卫(R1)的前端回归:
//       sameOrigin 与后端语义一致;Base URL 改到不同来源时表单要求
//       显式决定密钥,同来源编辑不触发。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CustomProviderForm from '@/features/model-settings/components/CustomProviderForm.vue'
import { sameOrigin } from '@/shared/net/origin'
import {
  getCustomStatus,
} from '@/features/model-settings/api/modelProviders'

vi.mock('@/features/model-settings/api/modelProviders', () => ({
  getCustomStatus: vi.fn(),
  discoverCustom: vi.fn(),
  saveCustom: vi.fn(),
  saveCustomWithSource: vi.fn(),
  validateCustom: vi.fn(),
  getActiveProvider: vi.fn(),
  activateProvider: vi.fn(),
}))

const mockedGet = vi.mocked(getCustomStatus)

async function mountForm(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(CustomProviderForm, {
    props: { mode: 'edit', idPrefix: 'custom' },
  })
  await flushPromises()
  return wrapper
}

describe('sameOrigin semantics match the backend guard', () => {
  it('treats default ports as equal origins', () => {
    expect(sameOrigin('http://localhost:80/v1', 'http://localhost/v1')).toBe(true)
    expect(sameOrigin('https://gw.example.com:443/v1', 'https://GW.example.com/v1')).toBe(true)
  })

  it('treats different ports, schemes or hosts as different origins', () => {
    expect(sameOrigin('http://localhost:8080/v1', 'http://localhost/v1')).toBe(false)
    expect(sameOrigin('http://localhost/v1', 'https://localhost/v1')).toBe(false)
    expect(sameOrigin('http://localhost/v1', 'http://127.0.0.1/v1')).toBe(false)
  })

  it('is conservative for unparseable input', () => {
    expect(sameOrigin('not a url', 'http://localhost/v1')).toBe(false)
    expect(sameOrigin('', 'http://localhost/v1')).toBe(false)
  })
})

describe('CustomProviderForm origin-change credential guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('blocks discover and save until the key is explicitly decided on origin change', async () => {
    mockedGet.mockResolvedValue({
      configured: true,
      apiFormat: 'CHAT_COMPLETIONS',
      baseUrl: 'http://localhost:11434/v1',
      endpointPreview: null,
      hasKey: true,
      maskedKey: '••••K3Y9',
      selectedModel: 'm1',
      manualModel: false,
      displayName: 'Local',
      configRevision: 1,
      validated: true,
    })
    const wrapper = await mountForm()

    // 把地址改到不同来源
    await wrapper.get('[data-test="custom-base-url"]').setValue('http://localhost:19099/v1')

    // 明确提示,且获取模型/保存按钮被禁用
    expect(wrapper.find('[data-test="custom-origin-warning"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="custom-discover"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="custom-save-test"]').attributes('disabled')).toBeDefined()

    // 输入新密钥后解除
    await wrapper.get('[data-test="custom-api-key"]').setValue('brand-new-key')
    expect(wrapper.find('[data-test="custom-origin-warning"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="custom-discover"]').attributes('disabled')).toBeUndefined()
  })

  it('does not trigger for same-origin edits or keyless configs', async () => {
    mockedGet.mockResolvedValue({
      configured: true,
      apiFormat: 'CHAT_COMPLETIONS',
      baseUrl: 'http://localhost:11434/v1',
      endpointPreview: null,
      hasKey: false,
      maskedKey: null,
      selectedModel: 'm1',
      manualModel: false,
      displayName: 'Local',
      configRevision: 1,
      validated: true,
    })
    const wrapper = await mountForm()

    // 同来源的路径编辑不触发守卫
    await wrapper.get('[data-test="custom-base-url"]').setValue('http://localhost:11434/v2')
    expect(wrapper.find('[data-test="custom-origin-warning"]').exists()).toBe(false)

    // 无密钥配置改地址也不触发(没有可泄漏的凭据)
    await wrapper.get('[data-test="custom-base-url"]').setValue('http://other-host:9999/v1')
    expect(wrapper.find('[data-test="custom-origin-warning"]').exists()).toBe(false)
  })
})
