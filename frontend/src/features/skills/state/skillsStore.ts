// 文件名:skillsStore.ts
// 用途:Skill 的 Pinia 状态仓:已安装 Skill 与暂存导入的状态管理——列表/详情/版本/
//       资源加载、ZIP/Git 暂存与 Git 探测、安装/拒绝/删除、启用/禁用;
//       列表加载使用 loadToken 竞态守护。

import { defineStore } from 'pinia'
import { identityScopedStore, privateIdentityWork, PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'
import { toDisplayError } from '@/shared/http/displayError'
import { createLoadToken } from './raceGuard'
import { deleteSkill, deleteStagedImport, disableSkill, discoverSkillGit, enableSkill, getSkill, getStagedImport, installStagedImport, listSkillResources, listSkills, listSkillVersions, listStagedImports, readSkillResource, rejectStagedImport, stageSkillGit, stageSkillZip } from '@/features/skills/api/skills'
import type { GitSkillCandidate, SkillDetail, SkillResourceRead, SkillResourceSummary, SkillSummary, SkillVersionView, StagedImportDetail, StagedImportView } from '@/features/skills/api/skillTypes'

export interface SkillsStoreError {
  code: string
  message: string
}

const displayError: (err: unknown) => SkillsStoreError = toDisplayError

/**
 * 已安装 Skill 与暂存导入的状态。绝不触碰工作台或连接数据;
 * 不做语义路由:只响应用户的显式选择。
 */
const listLoadToken = createLoadToken()

export const useSkillsStore = defineStore('skills', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
    list: [] as SkillSummary[],
    detail: null as SkillDetail | null,
    versions: [] as SkillVersionView[],
    resources: [] as SkillResourceSummary[],
    resourceRead: null as SkillResourceRead | null,
    staged: [] as StagedImportDetail[],
    stagedDetail: null as StagedImportDetail | null,
    lastStaged: null as StagedImportView | null,
    gitCandidates: null as GitSkillCandidate[] | null,
    gitSuggestedPath: null as string | null,
    gitDiscovering: false,
    listLoading: false,
    detailLoading: false,
    actionLoading: false,
    error: null as SkillsStoreError | null,
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
      const token = listLoadToken.next()
      identityStore.listLoading = true
      identityStore.error = null
      try {
        const list = await privateIdentityWork(identityStore, identityEpoch, () => (listSkills()))
        if (listLoadToken.isCurrent(token)) {
          identityStore.list = list
        }
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        if (listLoadToken.isCurrent(token)) {
          identityStore.error = displayError(err)
        }
      } finally {
        if (this.identityEpoch === identityEpoch) {
        if (listLoadToken.isCurrent(token)) {
          identityStore.listLoading = false
        }
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async loadDetail(skillId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.detailLoading = true
      identityStore.error = null
      try {
        identityStore.detail = await privateIdentityWork(identityStore, identityEpoch, () => (getSkill(skillId)))
        identityStore.versions = await privateIdentityWork(identityStore, identityEpoch, () => (listSkillVersions(skillId)))
        identityStore.resources = await privateIdentityWork(identityStore, identityEpoch, () => (listSkillResources(skillId)))
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
    async readResource(skillId: string, path: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        identityStore.resourceRead = await privateIdentityWork(identityStore, identityEpoch, () => (readSkillResource(skillId, path)))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.actionLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async stageZip(file: File): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        identityStore.lastStaged = await privateIdentityWork(identityStore, identityEpoch, () => (stageSkillZip(file)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadStaged()))
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
    async stageGit(url: string, ref?: string, subPath?: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        identityStore.lastStaged = await privateIdentityWork(identityStore, identityEpoch, () => (stageSkillGit(url, ref, subPath)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadStaged()))
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
    /**
     * 列出仓库提供的 Skill 包,供浏览库/市场后选择。只读:探测不做任何暂存。
     */
    async discoverGit(url: string, ref?: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.gitDiscovering = true
      identityStore.error = null
      try {
        const discovery = await privateIdentityWork(identityStore, identityEpoch, () => (discoverSkillGit(url, ref)))
        identityStore.gitCandidates = discovery.candidates
        identityStore.gitSuggestedPath = discovery.suggestedPath
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        identityStore.gitCandidates = null
        identityStore.gitSuggestedPath = null
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.gitDiscovering = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async loadStaged(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      try {
        identityStore.staged = await privateIdentityWork(identityStore, identityEpoch, () => (listStagedImports()))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async loadStagedDetail(stagedImportId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      try {
        identityStore.stagedDetail = await privateIdentityWork(identityStore, identityEpoch, () => (getStagedImport(stagedImportId)))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async installStaged(stagedImportId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (installStagedImport(stagedImportId)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadList()))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadStaged()))
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
    async rejectStaged(stagedImportId: string, reason?: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (rejectStagedImport(stagedImportId, reason)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadStaged()))
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
    async discardStaged(stagedImportId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (deleteStagedImport(stagedImportId)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadStaged()))
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
    async enable(skillId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      return identityStore.lifecycle(skillId, enableSkill)
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async disable(skillId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      return identityStore.lifecycle(skillId, disableSkill)
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async remove(skillId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      return identityStore.lifecycle(skillId, deleteSkill)
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async lifecycle(skillId: string, op: (id: string) => Promise<unknown>): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.actionLoading = true
      identityStore.error = null
      try {
        await privateIdentityWork(identityStore, identityEpoch, () => (op(skillId)))
        await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadList()))
        if (identityStore.detail?.skillId === skillId) {
          await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.loadDetail(skillId).catch(() => undefined)))
        }
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
    clearError(): void {
      this.error = null
    },
    /** 丢弃上一次的仓库探测结果,让新的导入从干净状态开始。 */
    resetGitDiscovery(): void {
      this.gitCandidates = null
      this.gitSuggestedPath = null
    },
  },
})
