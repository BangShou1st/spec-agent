// 文件名:skills.ts
// 用途:Skill 管理的 API 封装:列表/详情/版本/资源读取,ZIP 与 Git 的暂存导入、
//       Git 仓库探测、待导入的安装/拒绝/删除,以及启用/禁用/删除已装 Skill。
//       以后端 SkillController 为准;这里不做语义路由或关键字分支。

import { apiClient } from '@/shared/http/client'
import type { GitSkillDiscovery, InstalledSkillResult, SkillDetail, SkillResourceRead, SkillResourceSummary, SkillSummary, SkillVersionView, StagedImportDetail, StagedImportView } from './skillTypes'

/**
 * Skill 管理 API 封装。以后端 SkillController 为准;
 * 这里不存在语义路由或关键字分支。
 */

export function listSkills(): Promise<SkillSummary[]> {
  return apiClient.get<SkillSummary[]>('/skills')
}

export function getSkill(skillId: string): Promise<SkillDetail> {
  return apiClient.get<SkillDetail>(`/skills/${encodeURIComponent(skillId)}`)
}

export function listSkillVersions(skillId: string): Promise<SkillVersionView[]> {
  return apiClient.get<SkillVersionView[]>(`/skills/${encodeURIComponent(skillId)}/versions`)
}

export function listSkillResources(skillId: string): Promise<SkillResourceSummary[]> {
  return apiClient.get<SkillResourceSummary[]>(`/skills/${encodeURIComponent(skillId)}/resources`)
}

export function readSkillResource(skillId: string, path: string): Promise<SkillResourceRead> {
  return apiClient.get<SkillResourceRead>(`/skills/${encodeURIComponent(skillId)}/resources/read?path=${encodeURIComponent(path)}`)
}

export function stageSkillZip(file: File): Promise<StagedImportView> {
  const form = new FormData()
  form.append('file', file)
  return apiClient.postForm<StagedImportView>('/skills/imports/zip', form)
}

export function stageSkillGit(url: string, ref?: string, subPath?: string): Promise<StagedImportView> {
  return apiClient.post<StagedImportView>('/skills/imports/git', { url, ref, subPath })
}

/** 列出仓库提供的 Skill 包;不做任何暂存。 */
export function discoverSkillGit(url: string, ref?: string): Promise<GitSkillDiscovery> {
  return apiClient.post<GitSkillDiscovery>('/skills/imports/git/discover', { url, ref })
}

export function listStagedImports(): Promise<StagedImportDetail[]> {
  return apiClient.get<StagedImportDetail[]>('/skills/imports')
}

export function getStagedImport(stagedImportId: string): Promise<StagedImportDetail> {
  return apiClient.get<StagedImportDetail>(`/skills/imports/${encodeURIComponent(stagedImportId)}`)
}

export function installStagedImport(stagedImportId: string): Promise<InstalledSkillResult> {
  return apiClient.post<InstalledSkillResult>(`/skills/imports/${encodeURIComponent(stagedImportId)}/install`)
}

export function rejectStagedImport(stagedImportId: string, reason?: string): Promise<void> {
  return apiClient.post<void>(`/skills/imports/${encodeURIComponent(stagedImportId)}/reject`, reason == null ? {} : { reason })
}

export function deleteStagedImport(stagedImportId: string): Promise<void> {
  return apiClient.delete<void>(`/skills/imports/${encodeURIComponent(stagedImportId)}`)
}

export function enableSkill(skillId: string): Promise<void> {
  return apiClient.post<void>(`/skills/${encodeURIComponent(skillId)}/enable`)
}

export function disableSkill(skillId: string): Promise<void> {
  return apiClient.post<void>(`/skills/${encodeURIComponent(skillId)}/disable`)
}

export function deleteSkill(skillId: string): Promise<void> {
  return apiClient.delete<void>(`/skills/${encodeURIComponent(skillId)}`)
}
