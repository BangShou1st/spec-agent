import { canReadConnectionCapabilities } from '../connectionPermissions'
// 文件名:connectionsStore.ts
// 用途:连接(Connection)的 Pinia 状态仓:负责连接列表/详情与 tools/resources/prompts
//       能力数据的加载缓存,以及创建、编辑、测试、连接、刷新、启用/禁用、删除等生命周期操作;
//       只管理连接本身,不涉及工作台或 Skills,语义动作只由用户显式触发。

import { defineStore } from 'pinia'
import { identityScopedStore, privateIdentityWork, PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'
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
    identityEpoch: 0,
    privateIdentity: null as string | null,
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
    bindIdentity(identity: string | null): void {
      if (identity === this.privateIdentity && identity !== null) return
      const epoch = this.identityEpoch + 1
      this.$reset()
      this.identityEpoch = epoch
      this.privateIdentity = identity
    },
    async loadList(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.listLoading = true
      identityStore.error = null
      try {
        identityStore.list = await privateIdentityWork(identityStore, identityEpoch, () => (listConnections()))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.listLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async loadDetail(connectionId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.detailLoading = true
      identityStore.error = null
      try {
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (getConnection(connectionId)))
        identityStore.tools = canReadConnectionCapabilities(identityStore.detail)
          ? await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionTools(connectionId)))
          : []
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.detailLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async create(request: CreateConnectionRequest): Promise<ConnectionSummary | null> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        const created = await privateIdentityWork(identityStore, identityEpoch, () => (createConnection(request)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadList()))
        return created
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return null
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return null }
        throw identityError
      }
    },
    async update(connectionId: string, patch: UpdateConnectionRequest): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (updateConnection(connectionId, patch)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadList()))
        identityStore.tools = canReadConnectionCapabilities(identityStore.detail)
          ? await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionTools(connectionId)))
          : []
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async test(connectionId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (testConnection(connectionId)))
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (getConnection(connectionId)))
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async connect(connectionId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (connectConnection(connectionId)))
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (getConnection(connectionId)))
        identityStore.tools = canReadConnectionCapabilities(identityStore.detail)
          ? await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionTools(connectionId)))
          : []
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async refresh(connectionId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (refreshConnection(connectionId)))
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (getConnection(connectionId)))
        identityStore.tools = canReadConnectionCapabilities(identityStore.detail)
          ? await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionTools(connectionId)))
          : []
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async enable(connectionId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      return identityStore.simpleLifecycle(connectionId, enableConnection)
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async disable(connectionId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      return identityStore.simpleLifecycle(connectionId, disableConnection)
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async remove(connectionId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (deleteConnection(connectionId)))
        if (identityStore.detail?.connectionId === connectionId) {
          identityStore.detail = null
          identityStore.tools = []
          identityStore.resources = []
          identityStore.prompts = []
        }
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadList()))
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async simpleLifecycle(connectionId: string, op: (id: string) => Promise<unknown>): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (op(connectionId)))
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (getConnection(connectionId)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadList()))
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async loadTools(connectionId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        identityStore.error = null
        try {
          identityStore.tools = await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionTools(connectionId)))
        } catch (err) {
          if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

          identityStore.error = displayError(err)
        }
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async loadResources(connectionId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      try {
        identityStore.resources = await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionResources(connectionId)))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async loadPrompts(connectionId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      try {
        identityStore.prompts = await privateIdentityWork(identityStore, identityEpoch, () => (listConnectionPrompts(connectionId)))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async readResource(connectionId: string, uri: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      try {
        identityStore.resourceContent = await privateIdentityWork(identityStore, identityEpoch, () => (readConnectionResource(connectionId, uri)))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    clearError(): void {
      this.error = null
    },
  },
})
