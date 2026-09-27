// 文件名:skillsStore.ts
// 用途:Skill 的 Pinia 状态仓:已安装 Skill 与暂存导入的状态管理——列表/详情/版本/
//       资源加载、ZIP/Git 暂存与 Git 探测、安装/拒绝/删除、启用/禁用;
//       列表加载使用 loadToken 竞态守护。

import { defineStore } from 'pinia'
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
    async loadList(): Promise<void> {
      const token = listLoadToken.next()
      this.listLoading = true
      this.error = null
      try {
        const list = await listSkills()
        if (listLoadToken.isCurrent(token)) {
          this.list = list
        }
      } catch (err) {
        if (listLoadToken.isCurrent(token)) {
          this.error = displayError(err)
        }
      } finally {
        if (listLoadToken.isCurrent(token)) {
          this.listLoading = false
        }
      }
    },
    async loadDetail(skillId: string): Promise<void> {
      this.detailLoading = true
      this.error = null
      try {
        this.detail = await getSkill(skillId)
        this.versions = await listSkillVersions(skillId)
        this.resources = await listSkillResources(skillId)
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.detailLoading = false
      }
    },
    async readResource(skillId: string, path: string): Promise<void> {
      this.actionLoading = true
      this.error = null
      try {
        this.resourceRead = await readSkillResource(skillId, path)
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.actionLoading = false
      }
    },
    async stageZip(file: File): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        this.lastStaged = await stageSkillZip(file)
        await this.loadStaged()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async stageGit(url: string, ref?: string, subPath?: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        this.lastStaged = await stageSkillGit(url, ref, subPath)
        await this.loadStaged()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    /**
     * 列出仓库提供的 Skill 包,供浏览库/市场后选择。只读:探测不做任何暂存。
     */
    async discoverGit(url: string, ref?: string): Promise<boolean> {
      this.gitDiscovering = true
      this.error = null
      try {
        const discovery = await discoverSkillGit(url, ref)
        this.gitCandidates = discovery.candidates
        this.gitSuggestedPath = discovery.suggestedPath
        return true
      } catch (err) {
        this.error = displayError(err)
        this.gitCandidates = null
        this.gitSuggestedPath = null
        return false
      } finally {
        this.gitDiscovering = false
      }
    },
    async loadStaged(): Promise<void> {
      this.error = null
      try {
        this.staged = await listStagedImports()
      } catch (err) {
        this.error = displayError(err)
      }
    },
    async loadStagedDetail(stagedImportId: string): Promise<void> {
      this.error = null
      try {
        this.stagedDetail = await getStagedImport(stagedImportId)
      } catch (err) {
        this.error = displayError(err)
      }
    },
    async installStaged(stagedImportId: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await installStagedImport(stagedImportId)
        await this.loadList()
        await this.loadStaged()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async rejectStaged(stagedImportId: string, reason?: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await rejectStagedImport(stagedImportId, reason)
        await this.loadStaged()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async discardStaged(stagedImportId: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await deleteStagedImport(stagedImportId)
        await this.loadStaged()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
      }
    },
    async enable(skillId: string): Promise<boolean> {
      return this.lifecycle(skillId, enableSkill)
    },
    async disable(skillId: string): Promise<boolean> {
      return this.lifecycle(skillId, disableSkill)
    },
    async remove(skillId: string): Promise<boolean> {
      return this.lifecycle(skillId, deleteSkill)
    },
    async lifecycle(skillId: string, op: (id: string) => Promise<unknown>): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        await op(skillId)
        await this.loadList()
        if (this.detail?.skillId === skillId) {
          await this.loadDetail(skillId).catch(() => undefined)
        }
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
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
