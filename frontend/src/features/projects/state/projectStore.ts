// 文件名:projectStore.ts
// 用途:项目列表/创建的 Pinia 状态仓:列表加载(带乱序守护)、创建、原地重命名与删除;
//       后端始终是权威数据源,这里只镜像列表数据与创建结果。

import { defineStore } from 'pinia'
import { createProject, deleteProject, listProjects, renameProject } from '@/features/projects/api/projects'
import { toDisplayError, type DisplayError } from '@/shared/http/displayError'
import type { ProjectResponse, ProjectSummaryResponse } from '@/shared/contracts/types'

// 模块级单调递增令牌,用于 loadProjects 的竞态安全。
let loadToken = 0

/**
 * 项目列表/创建的应用状态。一切以后端为准;
 * 这个 store 只镜像列表数据与创建结果。
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
    projects: [] as ProjectSummaryResponse[],
    loading: false,
    creating: false,
    deletingId: null as string | null,
    renamingId: null as string | null,
    error: null as DisplayError | null,
  }),
  actions: {
    bindIdentity(identity: string | null): void {
      if (identity === this.privateIdentity && identity !== null) return
      const epoch = this.identityEpoch + 1
      loadToken++
      this.$reset()
      this.identityEpoch = epoch
      this.privateIdentity = identity
    },
    // 单调递增令牌:迟到的旧列表响应绝不能覆盖更新的响应。
    // 输入会同时触发多个在途请求,只有最新一次生效。
    // 这比防抖更轻,列表保持即时响应。
    async loadProjects(title?: string): Promise<void> {
      const epoch = this.identityEpoch
      const token = ++loadToken
      this.loading = true
      this.error = null
      try {
        const result = await listProjects(title)
        if (epoch === this.identityEpoch && token === loadToken) {
          this.projects = result
        }
      } catch (err) {
        if (epoch === this.identityEpoch && token === loadToken) {
          this.error = toDisplayError(err)
        }
      } finally {
        if (epoch === this.identityEpoch && token === loadToken) {
          this.loading = false
        }
      }
    },

    async createProject(title: string): Promise<ProjectResponse | null> {
      const epoch = this.identityEpoch
      if (this.creating) {
        return null
      }
      this.creating = true
      this.error = null
      try {
        const project = await createProject(title)
        if (epoch !== this.identityEpoch) return null
        this.projects = [...this.projects, { ...project }]
        return project
      } catch (err) {
        if (epoch !== this.identityEpoch) return null
        this.error = toDisplayError(err)
        return null
      } finally {
        if (epoch === this.identityEpoch) this.creating = false
      }
    },

    /** 原地重命名一个项目;列表顺序与创建历史保持不动。 */
    async renameProject(id: string, title: string): Promise<boolean> {
      const epoch = this.identityEpoch
      if (this.renamingId) return false
      this.renamingId = id
      this.error = null
      try {
        const updated = await renameProject(id, title)
        if (epoch !== this.identityEpoch) return false
        this.projects = this.projects.map((p) => (p.id === id ? { ...p, title: updated.title, updatedAt: updated.updatedAt } : p))
        return true
      } catch (err) {
        if (epoch !== this.identityEpoch) return false
        this.error = toDisplayError(err)
        return false
      } finally {
        if (epoch === this.identityEpoch) this.renamingId = null
      }
    },

    async deleteProject(id: string): Promise<boolean> {
      const epoch = this.identityEpoch
      if (this.deletingId) return false
      this.deletingId = id
      this.error = null
      try {
        await deleteProject(id)
        if (epoch !== this.identityEpoch) return false
        this.projects = this.projects.filter((p) => p.id !== id)
        return true
      } catch (err) {
        if (epoch !== this.identityEpoch) return false
        this.error = toDisplayError(err)
        return false
      } finally {
        if (epoch === this.identityEpoch) this.deletingId = null
      }
    },
  },
})
