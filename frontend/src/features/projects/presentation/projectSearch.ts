// 文件名:projectSearch.ts
// 用途:项目列表搜索——只为高亮服务的客户端辅助函数。
//
// 过滤已迁移到后端:`GET /projects?title=` 返回命中的子集(大小写不敏感的子串匹配),
// 权威结果集在服务端,规模与列表大小无关。前端保留两个纯函数,
// 因为高亮仍然需要原始查询词:
//
//  - `normalizeQuery` 去除首尾空白;全空白的查询等同于"没有查询词"。
//  - `splitTitleSegments` 把标题拆成 普通/命中 片段用于高亮渲染。
//    它返回数据而不是 HTML,视图因此永远不需要 v-html——
//    项目标题是用户输入,绝不能被当作标记注入。

export interface TitleSegment {
  text: string
  matched: boolean
}

/** 去除原始输入的首尾空白;全空白的查询等同于"没有查询词"。 */
export function normalizeQuery(query: string): string {
  return query.trim()
}

/**
 * 把标题拆成 普通/命中 片段用于高亮渲染。
 * 匹配规则镜像后端过滤器,保证高亮与结果集永远一致:
 *  - 查询词去除首尾空白;空查询返回单个未命中片段
 *  - 大小写不敏感的子串匹配
 *  - 所有出现位置都高亮,而不只是第一处
 */
export function splitTitleSegments(title: string, query: string): TitleSegment[] {
  const needle = normalizeQuery(query)
  if (!needle) return [{ text: title, matched: false }]

  const haystack = title.toLowerCase()
  const lowered = needle.toLowerCase()
  const segments: TitleSegment[] = []
  let cursor = 0
  let index = haystack.indexOf(lowered)

  while (index !== -1) {
    if (index > cursor) {
      segments.push({ text: title.slice(cursor, index), matched: false })
    }
    segments.push({ text: title.slice(index, index + lowered.length), matched: true })
    cursor = index + lowered.length
    index = haystack.indexOf(lowered, cursor)
  }
  if (cursor < title.length) {
    segments.push({ text: title.slice(cursor), matched: false })
  }
  if (segments.length === 0) {
    segments.push({ text: title, matched: false })
  }
  return segments
}
