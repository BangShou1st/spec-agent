/**
 * Defensive localStorage access. Storage can be unavailable (private mode,
 * quota, embedded webviews); callers treat a failure as "no preference" and
 * the backend stays canonical.
 */

export function readStored(key: string): string | null {
  try {
    const value = localStorage.getItem(key)
    return value && value.length > 0 ? value : null
  } catch {
    return null
  }
}

export function writeStored(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    /* storage unavailable: backend remains canonical */
  }
}
