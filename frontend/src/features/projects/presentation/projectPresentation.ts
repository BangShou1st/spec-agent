// 文件名:projectPresentation.ts
// 用途:项目页的展示层格式化:后端数据原样保留,只有渲染文本在此映射。

import { formatShanghaiDateTime } from '@/shared/lib/formatTime'

export function formatProjectCreatedAt(iso: string): string {
  return formatShanghaiDateTime(iso)
}
