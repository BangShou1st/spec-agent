/**
 * Skill management DTOs. Backend SkillController responses are authority;
 * these types mirror them and never guess backend state.
 */

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
