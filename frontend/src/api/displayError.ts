import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'

/**
 * The shape stores expose to views for error banners. Built from the unified
 * {@link ApiError} contract; anything else collapses to the generic message.
 */
export interface DisplayError {
  code: string
  message: string
  status?: number
}

export function toDisplayError(err: unknown): DisplayError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message, status: err.status }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}
