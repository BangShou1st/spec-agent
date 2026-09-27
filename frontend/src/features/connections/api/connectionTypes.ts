// 文件名:connectionTypes.ts
// 用途:连接(Connection)管理的 DTO 类型定义。以后端 ConnectionController 的响应为准;
//       config 只承载非敏感的元数据,secret 绝不会出现在响应中。

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
