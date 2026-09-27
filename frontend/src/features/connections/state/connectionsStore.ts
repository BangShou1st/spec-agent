// 文件名:connectionsStore.ts
// 用途:连接(Connection)的 Pinia 状态仓:负责连接列表/详情与 tools/resources/prompts
//       能力数据的加载缓存,以及创建、编辑、测试、连接、刷新、启用/禁用、删除等生命周期操作;
//       只管理连接本身,不涉及工作台或 Skills,语义动作只由用户显式触发。

import { defineStore } from 'pinia'
import { toDisplayError } from '@/shared/http/displayError'
import { connectConnection, createConnection, deleteConnection, disableConnection, enableConnection, getConnection, listConnectionPrompts, listConnectionResources, listConnectionTools, listConnections, readConnectionResource, refreshConnection, testConnection, updateConnection } from '@/features/connections/api/connections'
import type { ConnectionDetail, ConnectionPromptView, ConnectionResourceContent, ConnectionResourceView, ConnectionSummary, ConnectionToolView, CreateConnectionRequest, UpdateConnectionRequest } from '@/features/connections/api/connectionTypes'

export interface ConnectionsStoreError {
  code: string
  message: string
}

const displayError: (err: unknown) => ConnectionsStoreError = toDisplayError

/**
 * 已保存连接与 MCP 原语(工具/资源/prompt)的读取。
 * 不触碰工作台或 Skills 数据;不做语义路由,只有用户显式触发的生命周期操作。
 */
export const useConnectionsStore = defineStore('connections', {
  state: () => ({
    list: [] as ConnectionSummary[],
    detail: null as ConnectionDetail | null,
    tools: [] as ConnectionToolView[],
    resources: [] as ConnectionResourceView[],
    prompts: [] as ConnectionPromptView[],
    resourceContent: null as ConnectionResourceContent | null,
    listLoading: false,
    detailLoading: false,
    actionLoading: false,
    error: null as ConnectionsStoreError | null,
  }),
  actions: {
    async loadList(): Promise<void> {
      this.listLoading = true
      this.error = null
      try {
        this.list = await listConnections()
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.listLoading = false
      }
    },
    async loadDetail(connectionId: string): Promise<void> {
      this.detailLoading = true
      this.error = null
      try {
        this.detail = await getConnection(connectionId)
        this.tools = await listConnectionTools(connectionId)
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.detailLoading = false
      }
    },
    async create(request: CreateConnectionRequest): Promise<ConnectionSummary | null> {
      this.actionLoading = true
      this.error = null
      try {
        const created = await createConnection(request)
        await this.loadList()
        return created
      } catch (err) {
        this.error = displayError(err)
        return null
      } finally {
        this.actionLoading = false
      }
    },
    async update(connectionId: string, patch: UpdateConnectionRequest): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        this.detail = await updateConnection(connectionId, patch)
        await this.loadList()
        this.tools = await listConnectionTools(connectionId)
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async test(connectionId: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await testConnection(connectionId)
        this.detail = await getConnection(connectionId)
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async connect(connectionId: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await connectConnection(connectionId)
        this.detail = await getConnection(connectionId)
        this.tools = await listConnectionTools(connectionId)
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async refresh(connectionId: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await refreshConnection(connectionId)
        this.detail = await getConnection(connectionId)
        this.tools = await listConnectionTools(connectionId)
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async enable(connectionId: string): Promise<boolean> {
      return this.simpleLifecycle(connectionId, enableConnection)
    },
    async disable(connectionId: string): Promise<boolean> {
      return this.simpleLifecycle(connectionId, disableConnection)
    },
    async remove(connectionId: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await deleteConnection(connectionId)
        if (this.detail?.connectionId === connectionId) {
          this.detail = null
          this.tools = []
          this.resources = []
          this.prompts = []
        }
        await this.loadList()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async simpleLifecycle(connectionId: string, op: (id: string) => Promise<unknown>): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await op(connectionId)
        this.detail = await getConnection(connectionId)
        await this.loadList()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async loadResources(connectionId: string): Promise<void> {
      this.error = null
      try {
        this.resources = await listConnectionResources(connectionId)
      } catch (err) {
        this.error = displayError(err)
      }
    },
    async loadPrompts(connectionId: string): Promise<void> {
      this.error = null
      try {
        this.prompts = await listConnectionPrompts(connectionId)
      } catch (err) {
        this.error = displayError(err)
      }
    },
    async readResource(connectionId: string, uri: string): Promise<void> {
      this.error = null
      try {
        this.resourceContent = await readConnectionResource(connectionId, uri)
      } catch (err) {
        this.error = displayError(err)
      }
    },
    clearError(): void {
      this.error = null
    },
  },
})
