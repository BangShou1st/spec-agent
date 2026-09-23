/**
 * 资源类型判定 —— 全项目唯一一处「按扩展名决定怎么显示」的规则。
 *
 * 文档阅读器（FilePreviewDialog）与 Skill 资源查看器都从这里取结论，
 * 避免两边各写一份扩展名清单后慢慢走样。
 */

const MARKDOWN_EXTENSIONS = new Set(['md', 'markdown', 'mdx'])

const CODE_EXTENSIONS = new Set([
  'json', 'jsonc', 'yaml', 'yml', 'toml', 'ini', 'csv', 'tsv',
  'ts', 'tsx', 'js', 'jsx', 'mjs', 'cjs', 'vue', 'py', 'java', 'kt',
  'go', 'rs', 'rb', 'php', 'cs', 'c', 'h', 'cpp', 'hpp', 'sql', 'sh',
  'bat', 'ps1', 'xml', 'html', 'htm', 'css', 'scss', 'less', 'gradle', 'properties',
])

export type ResourceKind = 'markdown' | 'code' | 'text'

/** 从路径或文件名取小写扩展名；没有扩展名时返回空串。 */
export function resourceExtension(path: string | null | undefined): string {
  const name = path ?? ''
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/**
 * Markdown 走富文本排版，代码/数据保持缩进结构，其余按正文处理。
 * 判定只看扩展名，不看内容：同一扩展名在任何入口都应得到同一种呈现。
 */
export function resourceKindOf(path: string | null | undefined): ResourceKind {
  const extension = resourceExtension(path)
  if (MARKDOWN_EXTENSIONS.has(extension)) return 'markdown'
  if (CODE_EXTENSIONS.has(extension)) return 'code'
  return 'text'
}
