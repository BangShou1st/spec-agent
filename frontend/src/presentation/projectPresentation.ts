/**
 * Projects-page presentation formatting. Backend data stays untouched;
 * only the rendered text is mapped here.
 */

import { formatShanghaiDateTime } from '@/presentation/formatTime'

export function formatProjectCreatedAt(iso: string): string {
  return formatShanghaiDateTime(iso)
}
