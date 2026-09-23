/**
 * Resolves the browser-only reading route for a graph element.
 *
 * A single-route element has one unambiguous reading route. A shared element
 * may keep an already-valid browser Focus, but an absent/invalid Focus stays
 * neutral. Runtime Active is deliberately not an input: it is a work route,
 * never a reading-context fallback.
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

/**
 * Single source of truth for a node's READING ROUTE (the "当前查看" route) —
 * used by both the canvas projection and every command the card can issue
 * (fork / reanswer / regenerate / 继续 / 问 AI / 需求状态).
 *
 * Two independent resolvers previously disagreed, so a shared node could read
 * under route B while its actions silently had no source route at all. The
 * rules are deliberately deterministic and NEVER guess:
 *
 *  1. an explicit browser Focus that is a member wins — 只看这条路线 / 点卡片
 *     已经把它定死了，用户不需要再选一次；
 *  2. otherwise, exactly one member route is currently VISIBLE → that one
 *     (hiding/filtering every other member also collapses the ambiguity);
 *  3. otherwise → null (genuinely ambiguous; the card keeps the explicit
 *     picker and commands stay fail-closed).
 *
 * Active is not an input here either: it decides where answers are WRITTEN,
 * not which route the user is reading.
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
