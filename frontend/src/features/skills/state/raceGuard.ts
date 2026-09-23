/**
 * 列表加载竞态保护。projectStore 的 loadToken 模式提炼:
 * 慢的旧响应不得覆盖新的列表状态(slash 菜单每次打开都会重新 loadList,
 * 快速开合 + 慢网时会乱序写入)。
 */
export function createLoadToken(): {
  next: () => number
  isCurrent: (token: number) => boolean
} {
  let current = 0
  return {
    next: () => ++current,
    isCurrent: (token) => token === current,
  }
}
