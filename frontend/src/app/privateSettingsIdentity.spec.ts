import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { bindPrivateSettingsIdentity } from './privateSettingsIdentity'
import { useConnectionsStore } from '@/features/connections/state/connectionsStore'
import { useSkillsStore } from '@/features/skills/state/skillsStore'
import { useModelSettingsStore } from '@/features/model-settings/state/modelSettingsStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import { getConnection, listConnectionTools } from '@/features/connections/api/connections'
import { getOpenCodeSettings } from '@/features/model-settings/api/modelSettings'

vi.mock('@/features/connections/api/connections', async () => ({
  ...(await vi.importActual<typeof import('@/features/connections/api/connections')>('@/features/connections/api/connections')),
  getConnection: vi.fn(), listConnectionTools: vi.fn(),
}))
vi.mock('@/features/model-settings/api/modelSettings', async () => ({
  ...(await vi.importActual<typeof import('@/features/model-settings/api/modelSettings')>('@/features/model-settings/api/modelSettings')),
  getOpenCodeSettings: vi.fn(),
}))
describe('private settings identity lifecycle', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })
  it('resets every settings family on logout', () => {
    bindPrivateSettingsIdentity('alice/org')
    useConnectionsStore().error = { code: 'private', message: 'private' }
    useSkillsStore().error = { code: 'private', message: 'private' }
    useModelSettingsStore().selectedModel = 'private-model'
    useProviderSettingsStore().viewTouched = true
    useCustomProviderStore().error = { code: 'private', message: 'private' }
    bindPrivateSettingsIdentity(null)
    expect(useConnectionsStore().error).toBeNull()
    expect(useSkillsStore().error).toBeNull()
    expect(useModelSettingsStore().selectedModel).toBeNull()
    expect(useProviderSettingsStore().viewTouched).toBe(false)
    expect(useCustomProviderStore().error).toBeNull()
  })
  it('discards an old connection detail and does not request its tools after the identity changes', async () => {
    bindPrivateSettingsIdentity('alice/org')
    let finish!: (value: Awaited<ReturnType<typeof getConnection>>) => void
    vi.mocked(getConnection).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const store = useConnectionsStore(); const pending = store.loadDetail('alice-connection')
    bindPrivateSettingsIdentity('bob/org')
    finish({ connectionId: 'alice-connection' } as Awaited<ReturnType<typeof getConnection>>)
    await pending
    expect(store.detail).toBeNull()
    expect(listConnectionTools).not.toHaveBeenCalled()
  })
  it('rejects a model status arriving between promise settlement and the old continuation', async () => {
    bindPrivateSettingsIdentity('alice/org')
    let finish!: (value: Awaited<ReturnType<typeof getOpenCodeSettings>>) => void
    vi.mocked(getOpenCodeSettings).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const store = useModelSettingsStore(); const pending = store.loadStatus()
    finish({ selectedModel: 'alice-private-model' } as Awaited<ReturnType<typeof getOpenCodeSettings>>)
    queueMicrotask(() => bindPrivateSettingsIdentity('bob/org'))
    await pending
    expect(store.status).toBeNull()
    expect(store.selectedModel).toBeNull()
  })
})
