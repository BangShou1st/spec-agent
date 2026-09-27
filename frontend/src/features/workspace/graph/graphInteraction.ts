// 文件名:graphInteraction.ts
// 用途:画布交互的路线焦点决策工具:解析图元素的浏览器阅读路线意图,并为节点卡片提供唯一的"当前查看"路线判定规则,被画布投影与所有卡片命令共用。
/*
 * 解析图元素在浏览器端的阅读路线。
 *
 * 单路线元素只有一条明确的阅读路线。共享元素可以沿用仍然有效的浏览器
 * Focus,但 Focus 缺失/失效时保持中性。运行时 Active 故意不作为输入:
 * 它是工作路线,绝不用作阅读上下文的兜底。
 */
export function resolveRouteFocusIntent(
  visibleRouteIds: readonly string[],
  currentFocusRouteId: string | null,
): string | null {
  const uniqueRouteIds = [...new Set(visibleRouteIds)]
  if (uniqueRouteIds.length === 1) {
    return uniqueRouteIds[0]
  }

  if (currentFocusRouteId && uniqueRouteIds.includes(currentFocusRouteId)) {
    return currentFocusRouteId
  }
  return null
}

/*
 * 节点阅读路线("当前查看")的唯一事实来源——画布投影和卡片能发出的所有
 * 命令(fork / reanswer / regenerate / 继续 / 问 AI / 需求状态)都用它。
 *
 * 此前两个独立的判定器结果不一致,导致共享节点可能在路线 B 下阅读,而它的
 * 操作却根本没有来源路线。规则刻意保持确定性且绝不猜测:
 *
 *  1. 存在且属于成员路线的显式浏览器 Focus 直接生效——"只看这条路线"/
 *     点卡片已经把它定死,用户不需要再选一次;
 *  2. 否则,若恰好只有一条成员路线当前可见 → 选它
 *     (隐藏/筛选掉其它成员同样能消除歧义);
 *  3. 否则 → null(确实有歧义;卡片保留显式选择器,命令保持 fail-closed)。
 *
 * Active 同样不是输入:它决定回答"写到哪条路线",而不是用户正在阅读哪条。
 */
export function resolveReadingRouteId(input: {
  membershipRouteIds: readonly string[]
  visibleRouteIds: Iterable<string>
  focusRouteId: string | null
}): string | null {
  const membership = [...new Set(input.membershipRouteIds)]
  if (membership.length === 0) return null
  if (input.focusRouteId && membership.includes(input.focusRouteId)) {
    return input.focusRouteId
  }
  const visible = new Set(input.visibleRouteIds)
  const visibleMembership = membership.filter((routeId) => visible.has(routeId))
  if (visibleMembership.length === 1) {
    return visibleMembership[0]
  }
  return null
}
