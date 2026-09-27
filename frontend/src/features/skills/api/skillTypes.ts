// 文件名:skillTypes.ts
// 用途:Skill 管理的 DTO 类型定义。以后端 SkillController 的响应为准;
//       这些类型只镜像后端,绝不猜测后端状态。

export interface SkillSummary {
  skillId: string
  name: string
  description: string
  sourceKind: string
  versionId: string | null
  enabled: boolean
  createdAt: string
}

export interface SkillDetail {
  skillId: string
  name: string
  description: string
  sourceKind: string
  sourceIdentity: string
  versionId: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface SkillVersionView {
  id: string
  versionNo: number
  contentHash: string
  fileCount: number
  totalBytes: number
  createdAt: string
}

export interface SkillResourceSummary {
  path: string
  kind: string
  sizeBytes: number
  sha256: string
}

export interface StagedImportView {
  stagedImportId: string
  name: string
  description: string
  contentHash: string
  fileCount: number
  totalBytes: number
}

export interface StagedImportDetail {
  stagedImportId: string
  sourceKind: string
  sourceIdentity: string
  manifest: string
  totalBytes: number
  fileCount: number
  contentHash: string
  status: string
  rejectedReason: string | null
  createdAt: string
}

/** 一个 Git 仓库提供的单个 Skill 包。path 为 '' 表示仓库根目录。 */
export interface GitSkillCandidate {
  path: string
  name: string
  description: string
  kind: string
  declaredBy: string | null
  fileCount: number
  parseable: boolean
}

export interface GitSkillDiscovery {
  commitSha: string
  suggestedPath: string | null
  candidates: GitSkillCandidate[]
}

export interface SkillResourceRead {
  relativePath: string
  content: string
  truncated: boolean
  totalChars: number
  sha256: string
  versionId: string
}

export interface InstalledSkillResult {
  skillId: string
  skillRowId: string
  versionId: string
  versionNo: number
}
