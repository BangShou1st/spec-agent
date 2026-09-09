import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { deleteSkill, deleteStagedImport, disableSkill, enableSkill, getSkill, getStagedImport, installStagedImport, listSkillResources, listSkills, listSkillVersions, listStagedImports, readSkillResource, rejectStagedImport, stageSkillGit, stageSkillZip } from '@/api/skills'
import type { SkillDetail, SkillResourceRead, SkillResourceSummary, SkillSummary, SkillVersionView, StagedImportDetail, StagedImportView } from '@/api/skillTypes'

export interface SkillsStoreError {
  code: string
  message: string
}

function displayError(err: unknown): SkillsStoreError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

/**
 * Installed Skill and staged-import state. Never touches workspace or connections.
 * No semantic routing: explicit user selections only.
 */
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
    listLoading: false,
    detailLoading: false,
    actionLoading: false,
    error: null as SkillsStoreError | null,
  }),
  actions: {
    async loadList(): Promise<void> {
      this.listLoading = true
      this.error = null
      try {
        this.list = await listSkills()
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.listLoading = false
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
    async stageGit(url: string, ref?: string): Promise<boolean> {
      this.actionLoading = true
      this.error = null
      try {
        this.lastStaged = await stageSkillGit(url, ref)
        await this.loadStaged()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.actionLoading = false
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
  },
})
