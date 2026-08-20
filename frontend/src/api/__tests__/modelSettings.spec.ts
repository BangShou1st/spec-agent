import { describe, expect, it, vi } from 'vitest'
import {
  getOpenCodeSettings,
  listOpenCodeModels,
  probeOpenCode,
  saveOpenCode,
  saveOpenCodeModel,
} from '@/api/modelSettings'

describe('model settings api', () => {
  it('uses the settings probe/save endpoints without exposing the key in reads', async () => {
    const get = vi.spyOn((await import('@/api/client')).apiClient, 'get').mockResolvedValue({
      configured: false,
      maskedKey: null,
      selectedModel: null,
    })
    const post = vi.spyOn((await import('@/api/client')).apiClient, 'post').mockResolvedValue({ freeModels: ['model-free'] })
    const put = vi.spyOn((await import('@/api/client')).apiClient, 'put').mockResolvedValue({
      configured: true,
      maskedKey: '…1234',
      selectedModel: 'model-free',
    })

    await expect(getOpenCodeSettings()).resolves.toMatchObject({ configured: false })
    await expect(probeOpenCode('secret')).resolves.toEqual({ freeModels: ['model-free'] })
    await expect(saveOpenCode('secret', 'model-free')).resolves.toMatchObject({ configured: true })
    expect(get).toHaveBeenCalledWith('/settings/opencode')
    expect(post).toHaveBeenCalledWith('/settings/opencode/probe', { apiKey: 'secret' })
    expect(put).toHaveBeenCalledWith('/settings/opencode', { apiKey: 'secret', selectedModel: 'model-free' })
    vi.restoreAllMocks()
  })

  it('uses saved-key model discovery and model-only save endpoints', async () => {
    const get = vi.spyOn((await import('@/api/client')).apiClient, 'get').mockResolvedValue({
      freeModels: ['alpha-free', 'beta-free'],
    })
    const put = vi.spyOn((await import('@/api/client')).apiClient, 'put').mockResolvedValue({
      configured: true,
      maskedKey: '••••1234',
      selectedModel: 'beta-free',
    })

    await expect(listOpenCodeModels()).resolves.toEqual({ freeModels: ['alpha-free', 'beta-free'] })
    await expect(saveOpenCodeModel('beta-free')).resolves.toMatchObject({ selectedModel: 'beta-free' })
    expect(get).toHaveBeenCalledWith('/settings/opencode/models')
    expect(put).toHaveBeenCalledWith('/settings/opencode/model', { selectedModel: 'beta-free' })
    vi.restoreAllMocks()
  })
})
