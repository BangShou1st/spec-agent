/**
 * Project list search — client-side helpers for the *highlight* only.
 *
 * Filtering moved to the backend: `GET /projects?title=` returns the matched
 * subset (case-insensitive substring), so the authoritative result set lives
 * on the server and scales independently of the list size. The frontend keeps
 * two pure helpers here because the highlight still needs the raw query term:
 *
 *  - `normalizeQuery` trims the input; an all-whitespace query means "no term".
 *  - `splitTitleSegments` splits a title into plain / matched segments for
 *    highlight rendering. It returns data instead of HTML so the view never
 *    needs `v-html` — project titles are user input and must never be injected
 *    as markup.
 */

export interface TitleSegment {
  text: string
  matched: boolean
}

/** Trims the raw input. An all-whitespace query means "no term". */
export function normalizeQuery(query: string): string {
  return query.trim()
}

/**
 * Splits a title into plain / matched segments for highlight rendering.
 * Matching rules mirror the backend filter so the highlight and the result
 * set never disagree:
 *  - the query is trimmed; an empty query yields one unmatched segment
 *  - case-insensitive substring match
 *  - every occurrence is highlighted, not just the first one
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
