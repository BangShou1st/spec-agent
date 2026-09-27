// 文件名:displayError.ts
// 用途:错误横幅的展示层错误类型与转换:把任意抛出的错误折叠为 DisplayError,未知错误一律落到通用文案。
import { ApiError, GENERIC_ERROR_MESSAGE } from './client'

/*
 * store 呈现给视图的错误横幅形态。由统一的 {@link ApiError} 契约构建;
 * 其它任何错误都折叠为通用文案。
 */
export interface DisplayError {
  code: string
  message: string
  status?: number
  details?: Record<string, string>
}

export function toDisplayError(err: unknown): DisplayError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message, status: err.status, details: err.details }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}
