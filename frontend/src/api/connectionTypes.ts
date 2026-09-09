/**
 * Connection management DTOs. Backend ConnectionController responses are authority.
 * Config is validated non-secret metadata; secrets never appear in responses.
 */

export interface ConnectionSummary {
  connectionId: string
  name: string
  kind: string
  status: string
  enabled: boolean
  config: Record<string, unknown>
  hasCredential: boolean
  maskedSuffix: string | null
  createdAt: string
  updatedAt: string
}

export interface ConnectionDetail {
  connectionId: string
  name: string
  kind: string
  status: string
  enabled: boolean
  config: Record<string, unknown>
  hasCredential: boolean
  maskedSuffix: string | null
  lastError: string | null
  createdAt: string
  updatedAt: string
}

export interface ConnectionDiscovery {
  serverInfo: string
  protocolVersion: string
  toolCount: number
  resourceCount: number
  promptCount: number
  toolNames: string[]
  resourceUris: string[]
}

export interface ConnectionToolView {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  annotations: Record<string, unknown>
}

export interface ConnectionResourceView {
  uri: string
  name: string
  description: string
  mimeType: string
}

export interface ConnectionPromptView {
  name: string
  description: string
  argumentCount: number
}

export interface ConnectionResourceContent {
  uri: string
  text: string
  mimeType: string
  provenance: Record<string, unknown>
}

export interface CreateConnectionRequest {
  kind: string
  name: string
  config?: Record<string, unknown>
  secret?: string
}

export interface UpdateConnectionRequest {
  name?: string
  config?: Record<string, unknown>
  secret?: string
}
