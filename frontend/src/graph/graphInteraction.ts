/**
 * Resolves the browser-only reading route for a graph element.
 *
 * Runtime Active Route is only used as a deterministic fallback when it is a
 * member of the clicked element. A shared element with no valid Focus/Active
 * membership deliberately returns null so the node-local route chooser can
 * resolve it without guessing.
 */
export function resolveRouteFocusIntent(
  routeIds: readonly string[],
  currentFocusRouteId: string | null,
  activeRouteId: string | null,
): string | null {
  const uniqueRouteIds = [...new Set(routeIds)]
  if (uniqueRouteIds.length === 1) {
    return uniqueRouteIds[0]
  }

  if (currentFocusRouteId && uniqueRouteIds.includes(currentFocusRouteId)) {
    return currentFocusRouteId
  }

  if (activeRouteId && uniqueRouteIds.includes(activeRouteId)) {
    return activeRouteId
  }

  return null
}
