import { useConnectionsStore } from '@/features/connections/state/connectionsStore'
import { useSkillsStore } from '@/features/skills/state/skillsStore'
import { useModelSettingsStore } from '@/features/model-settings/state/modelSettingsStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { useOpenRouterStore } from '@/features/model-settings/state/openRouterStore'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'

/** App owns cross-feature lifecycle; feature stores remain independent. */
export function bindPrivateSettingsIdentity(identity: string | null): void {
  for (const create of [useConnectionsStore, useSkillsStore, useModelSettingsStore,
    useProviderSettingsStore, useOpenRouterStore, useCustomProviderStore]) {
    create().bindIdentity(identity)
  }
}
