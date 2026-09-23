import type { SpecSnapshotResponse, SourceReferenceResponse } from '@/shared/contracts/types'

/**
 * Spec 快照的展示层纯函数:排序、选中兜底、来源引用去重。
 * 原实现散在 SpecDock / SpecSnapshotPanel / SpecSnapshotList 三处,
 * 后两者已删除,唯一定点收敛在这里(带单测)。
 */

export function sortSnapshotsDesc(
  snapshots: SpecSnapshotResponse[],
): SpecSnapshotResponse[] {
  return [...snapshots].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}

/** 选中 id 优先;未选中(或已失效)时兜底为最新一条。 */
export function resolveSelectedSpec(
  snapshots: SpecSnapshotResponse[],
  selectedSpecId: string | null,
): SpecSnapshotResponse | null {
  if (selectedSpecId) {
    return snapshots.find((snapshot) => snapshot.id === selectedSpecId) ?? null
  }
  return sortSnapshotsDesc(snapshots)[0] ?? null
}

/** sourceRefs 按 kind:refId 去重,保持首次出现顺序。 */
export function dedupeSourceRefs(
  refs: SourceReferenceResponse[],
): SourceReferenceResponse[] {
  const seen = new Set<string>()
  return refs.filter((ref) => {
    const key = `${ref.kind}:${ref.refId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
