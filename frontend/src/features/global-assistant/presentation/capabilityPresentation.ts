// 文件名:capabilityPresentation.ts
// 用途:全局助手能力(capability)展示元数据的中央注册表。
// 标签只来自这个按 capabilityId 索引的注册表,绝不取自用户 prompt 文本、模型正文、
// 项目名或组件内的特判;新增工具只需在此加一条,Vue 组件无需感知;
// 未知 id 走通用兜底,保证 UI 不崩溃。

export interface CapabilityPresentation {
  /** 活动行的简短动作名词,例如"搜索项目"。 */
  actionLabel: string;
  /** 通用条数渲染使用的结果类型,例如 PROJECT_LIST;没有则为 null。 */
  resultKind: string | null;
}

const REGISTRY: Record<string, CapabilityPresentation> = {
  'web.search': { actionLabel: '搜索网页', resultKind: 'WEB_SOURCE' },
  'web.fetch': { actionLabel: '读取网页', resultKind: 'WEB_SOURCE' },
  'project.create': { actionLabel: '创建项目', resultKind: 'PROJECT' },
  'project.search': { actionLabel: '搜索项目', resultKind: 'PROJECT_LIST' },
  'project.list_recent': { actionLabel: '查看最近项目', resultKind: 'PROJECT_LIST' },
  'project.get_summary': { actionLabel: '读取项目概要', resultKind: 'PROJECT' },
  'help.search': { actionLabel: '查询使用帮助', resultKind: 'SOURCE_LIST' },
  'project.content.discover': { actionLabel: '检索项目内容', resultKind: 'PROJECT_LIST' },
  'skill.import.discover': { actionLabel: '检查 Skill 仓库', resultKind: null },
  'ui.navigate': { actionLabel: '打开页面', resultKind: null },
  'user-input.request': { actionLabel: '请求补充信息', resultKind: null },
  'skill.import': { actionLabel: '获取 Skill', resultKind: null },
};

const FALLBACK: CapabilityPresentation = { actionLabel: '执行操作', resultKind: null };

export function capabilityPresentation(capabilityId: string): CapabilityPresentation {
  if (!capabilityId) return FALLBACK;
  return REGISTRY[capabilityId] ?? FALLBACK;
}

/**
 * 完成态摘要行,只根据真实结果条数生成。
 * 没有可信条数时返回 null,由调用方回退到后端 summary 字符串。
 * 绝不编造数字。
 */
export function completedCountLabel(capabilityId: string, count: number | null | undefined): string | null {
  if (count === null || count === undefined || !Number.isInteger(count) || (count as number) < 0) return null;
  const kind = capabilityPresentation(capabilityId).resultKind;
  if (kind === 'PROJECT_LIST') return '已获取 ' + count + ' 个项目';
  if (kind === 'SOURCE_LIST') return '已找到 ' + count + ' 条来源';
  if (kind === 'WEB_SOURCE') return count === 0 ? '没有网页结果' : '已获取 ' + count + ' 条网页来源';
  if (kind === 'PROJECT') return '已就绪';
  return null;
}
