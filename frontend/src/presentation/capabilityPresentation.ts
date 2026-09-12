/**
 * Central presentation metadata for Global Assistant capabilities.
 *
 * Labels come only from this registry keyed by capability id - never from
 * user prompt text, model prose, project names, or per-component special cases.
 * A new tool only needs one entry here; no Vue component learns new tools.
 * Unknown ids use the generic fallback so the UI never crashes.
 */
export interface CapabilityPresentation {
  /** Short action noun for the activity row, e.g. candidate search. */
  actionLabel: string;
  /** Result kind for generic count rendering, e.g. PROJECT_LIST. Null when none. */
  resultKind: string | null;
}

const REGISTRY: Record<string, CapabilityPresentation> = {
  'project.create': { actionLabel: '创建项目', resultKind: 'PROJECT' },
  'project.search': { actionLabel: '搜索项目', resultKind: 'PROJECT_LIST' },
  'project.list_recent': { actionLabel: '查看最近项目', resultKind: 'PROJECT_LIST' },
  'project.get_summary': { actionLabel: '读取项目概要', resultKind: 'PROJECT' },
};

const FALLBACK: CapabilityPresentation = { actionLabel: '执行操作', resultKind: null };

export function capabilityPresentation(capabilityId: string): CapabilityPresentation {
  if (!capabilityId) return FALLBACK;
  return REGISTRY[capabilityId] ?? FALLBACK;
}

/**
 * Completed-state line derived ONLY from the real result count.
 * Returns null when no trustworthy count exists so callers fall back
 * to the backend summary string. Never invents numbers.
 */
export function completedCountLabel(capabilityId: string, count: number | null | undefined): string | null {
  if (count === null || count === undefined || !Number.isInteger(count) || (count as number) < 0) return null;
  const kind = capabilityPresentation(capabilityId).resultKind;
  if (kind === 'PROJECT_LIST') return '已获取 ' + count + ' 个项目';
  if (kind === 'PROJECT') return '已就绪';
  return null;
}