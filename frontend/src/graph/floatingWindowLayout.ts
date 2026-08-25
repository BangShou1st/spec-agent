import type { FloatingWindowPreference } from './graphTypes'

export interface FloatingWindowRange {
  minWidth: number
  maxWidth: number
  minHeight: number
  maxHeight: number
}

export interface FloatingRect {
  x: number
  y: number
  width: number
  height: number
}

export interface AutoFloatingWindowLayoutInput {
  viewportWidth: number
  viewportHeight: number
  state: FloatingWindowPreference
  range: FloatingWindowRange
  obstacles: FloatingRect[]
  protectedObstacles?: FloatingRect[]
  margin?: number
  gap?: number
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function dimension(value: number, min: number, max: number, available: number): number {
  const preferred = clamp(value, min, max)
  return Math.min(preferred, Math.max(1, available))
}

function intersectionArea(a: FloatingRect, b: FloatingRect, padding: number): number {
  const left = Math.max(a.x, b.x - padding)
  const top = Math.max(a.y, b.y - padding)
  const right = Math.min(a.x + a.width, b.x + b.width + padding)
  const bottom = Math.min(a.y + a.height, b.y + b.height + padding)
  return Math.max(0, right - left) * Math.max(0, bottom - top)
}

export function floatingRectsOverlap(a: FloatingRect, b: FloatingRect, padding = 0): boolean {
  return intersectionArea(a, b, padding) > 0
}

/**
 * Chooses the least-obstructed position for a floating window. Candidates are
 * derived from the current viewport and obstacle rectangles; the persisted
 * position is only a preference, never a special-case coordinate.
 */
export function computeAutoFloatingWindowLayout({
  viewportWidth,
  viewportHeight,
  state,
  range,
  obstacles,
  protectedObstacles = [],
  margin = 16,
  gap = 16,
}: AutoFloatingWindowLayoutInput): FloatingWindowPreference {
  const preferredWidth = dimension(state.width, range.minWidth, range.maxWidth, viewportWidth - margin * 2)
  const preferredHeight = dimension(state.height, range.minHeight, range.maxHeight, viewportHeight - margin * 2)
  const dimensions: Array<{ width: number; height: number }> = [
    { width: preferredWidth, height: preferredHeight },
  ]
  const addDimension = (width: number, height: number): void => {
    const candidate = { width, height }
    if (!dimensions.some((item) => item.width === width && item.height === height)) {
      dimensions.push(candidate)
    }
  }

  // On a small viewport, a preferred window can be wider than the free space
  // beside a node. Try a constrained responsive width before accepting
  // overlap; manual resizing still enforces the normal minimum width.
  const responsiveMinWidth = Math.max(200, range.minWidth - 80)
  const responsiveMinHeight = Math.max(220, range.minHeight - 80)
  const responsiveWidths: number[] = []
  const responsiveHeights: number[] = []
  for (const obstacle of obstacles) {
    const leftSpace = obstacle.x - margin - gap
    const rightSpace = viewportWidth - obstacle.x - obstacle.width - margin - gap
    for (const available of [leftSpace, rightSpace]) {
      if (available >= responsiveMinWidth) {
        const width = Math.min(preferredWidth, available)
        addDimension(width, preferredHeight)
        responsiveWidths.push(width)
      }
    }
    const topSpace = obstacle.y - margin - gap
    const bottomSpace = viewportHeight - obstacle.y - obstacle.height - margin - gap
    for (const available of [topSpace, bottomSpace]) {
      if (available >= responsiveMinHeight) {
        const height = Math.min(preferredHeight, available)
        addDimension(preferredWidth, height)
        responsiveHeights.push(height)
      }
    }
  }
  for (const width of responsiveWidths) {
    for (const height of responsiveHeights) {
      addDimension(width, height)
    }
  }

  const candidates: Array<{ x: number; y: number; width: number; height: number }> = []
  const add = (width: number, height: number, x: number, y: number): void => {
    const maxX = Math.max(margin, viewportWidth - width - margin)
    const maxY = Math.max(margin, viewportHeight - height - margin)
    const candidate = {
      x: clamp(Number.isFinite(x) ? x : margin, margin, maxX),
      y: clamp(Number.isFinite(y) ? y : margin, margin, maxY),
      width,
      height,
    }
    if (!candidates.some((item) => item.x === candidate.x && item.y === candidate.y
      && item.width === width && item.height === height)) {
      candidates.push(candidate)
    }
  }

  for (const { width, height } of dimensions) {
    const maxX = Math.max(margin, viewportWidth - width - margin)
    const maxY = Math.max(margin, viewportHeight - height - margin)
    add(width, height, state.x, state.y)
    add(width, height, margin, margin)
    add(width, height, maxX, margin)
    add(width, height, margin, maxY)
    add(width, height, maxX, maxY)
    add(width, height, (viewportWidth - width) / 2, (viewportHeight - height) / 2)

    for (const obstacle of obstacles) {
      add(width, height, obstacle.x - width - gap, obstacle.y)
      add(width, height, obstacle.x + obstacle.width + gap, obstacle.y)
      add(width, height, obstacle.x, obstacle.y - height - gap)
      add(width, height, obstacle.x, obstacle.y + obstacle.height + gap)
      add(width, height, obstacle.x - width - gap, obstacle.y + obstacle.height - height)
      add(width, height, obstacle.x + obstacle.width + gap, obstacle.y + obstacle.height - height)
    }
  }

  const target: FloatingRect = {
    x: state.x,
    y: state.y,
    width: preferredWidth,
    height: preferredHeight,
  }
  const scored = candidates.map((candidate) => {
    const protectedOverlap = protectedObstacles.reduce(
      (sum, obstacle) => sum + intersectionArea(candidate, obstacle, gap),
      0,
    )
    const overlap = obstacles.reduce((sum, obstacle) => sum + intersectionArea(candidate, obstacle, gap), 0)
    const distance = Math.abs(candidate.x - target.x) + Math.abs(candidate.y - target.y)
    const sizeChange = Math.abs(candidate.width - preferredWidth) + Math.abs(candidate.height - preferredHeight)
    return { candidate, protectedOverlap, overlap, distance, sizeChange }
  })
  scored.sort((left, right) => left.protectedOverlap - right.protectedOverlap
    || left.overlap - right.overlap
    || left.sizeChange - right.sizeChange
    || left.distance - right.distance)
  const best = scored[0]?.candidate ?? {
    x: margin,
    y: margin,
    width: preferredWidth,
    height: preferredHeight,
  }

  return {
    ...state,
    x: best.x,
    y: best.y,
    width: best.width,
    height: best.height,
    positionMode: 'auto',
  }
}
