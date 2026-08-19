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
