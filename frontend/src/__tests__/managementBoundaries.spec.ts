import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import connectionsSource from '@/api/connections.ts?raw'
import skillsSource from '@/api/skills.ts?raw'
import connectionsStoreSource from '@/stores/connectionsStore.ts?raw'
import skillsStoreSource from '@/stores/skillsStore.ts?raw'

describe('management boundaries', () => {
  it('workspace store does not own skills or connections state', () => {
    setActivePinia(createPinia())
    const workspace = useWorkspaceStore()
    const state = workspace.$state as unknown as Record<string, unknown>
    for (const key of Object.keys(state)) {
      expect(key.toLowerCase()).not.toContain('skill')
      expect(key.toLowerCase()).not.toContain('connection')
    }
  })

  it('management modules do not route by provider keywords', () => {
    for (const source of [connectionsSource, skillsSource, connectionsStoreSource, skillsStoreSource]) {
      expect(source).not.toMatch(/github\.com|slack\.com|notion\.so/i)
      expect(source).not.toMatch(/if\s*\([^)]*github[^)]*\)/i)
      expect(source).not.toMatch(/if\s*\([^)]*slack[^)]*\)/i)
    }
  })

  it('frontend management never depends on the MCP SDK', () => {
    for (const source of [connectionsSource, skillsSource, connectionsStoreSource, skillsStoreSource]) {
      expect(source).not.toContain('modelcontextprotocol')
      expect(source).not.toContain('McpSyncClient')
      expect(source).not.toContain('HttpClientStreamableHttpTransport')
    }
  })
})
