import type { ApiErrorPayload, ApiFieldError } from './types'

/**
 * Typed API failure surfaced to the UI. `message` is always safe to render:
 * it is either the backend's sanitized stable message or the generic
 * frontend fallback. Raw response bodies, stack traces, HTML error pages,
 * and provider payloads are never exposed.
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly errors?: ApiFieldError[]

  constructor(message: string, code: string, status: number, errors?: ApiFieldError[]) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.errors = errors
  }
}

export const GENERIC_ERROR_MESSAGE = '操作失败，请稍后重试。'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

/**
 * One typed HTTP client for the Phase 6 API. Every response is parsed against
 * the stable API error contract; anything that does not match the contract
 * becomes a generic frontend fallback instead of leaking a raw body.
 */
class ApiClient {
  async get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'GET' })
  }

  async post<T>(path: string, body?: unknown): Promise<T> {
    const init: RequestInit = { method: 'POST' }
    if (body !== undefined) {
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        init.body = body as BodyInit
      } else {
        init.headers = { 'Content-Type': 'application/json' }
        init.body = JSON.stringify(body)
      }
    }
    return this.request<T>(path, init)
  }

  async put<T>(path: string, body?: unknown): Promise<T> {
    return this.requestWithBody<T>('PUT', path, body)
  }

  async patch<T>(path: string, body?: unknown): Promise<T> {
    return this.requestWithBody<T>('PATCH', path, body)
  }

  async delete<T = void>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'DELETE' })
  }

  async postForm<T>(path: string, form: FormData): Promise<T> {
    return this.request<T>(path, { method: 'POST', body: form })
  }

  private async requestWithBody<T>(method: string, path: string, body?: unknown): Promise<T> {
    const init: RequestInit = { method }
    if (body !== undefined) {
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        init.body = body
      } else {
        init.headers = { 'Content-Type': 'application/json' }
        init.body = JSON.stringify(body)
      }
    }
    return this.request<T>(path, init)
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}${path}`, init)
    } catch {
      throw new ApiError(GENERIC_ERROR_MESSAGE, 'NETWORK_ERROR', 0)
    }

    if (response.ok) {
      if (response.status === 204 || response.status === 205) {
        return undefined as T
      }
      const maybeText = (response as Response & { text?: unknown }).text
      if (typeof maybeText === 'function') {
        let textBody = ''
        try {
          textBody = await response.text()
        } catch {
          throw new ApiError(GENERIC_ERROR_MESSAGE, 'INVALID_RESPONSE', response.status)
        }
        if (!textBody) {
          return undefined as T
        }
        try {
          return JSON.parse(textBody) as T
        } catch {
          throw new ApiError(GENERIC_ERROR_MESSAGE, 'INVALID_RESPONSE', response.status)
        }
      }
      try {
        return (await response.json()) as T
      } catch {
        throw new ApiError(GENERIC_ERROR_MESSAGE, 'INVALID_RESPONSE', response.status)
      }
    }

    throw await this.toApiError(response)
  }

  /**
   * Parses a failure response against the API error contract
   * ({code, message, timestamp, errors}). A body that cannot be parsed as the
   * contract yields the generic fallback, never a raw body or HTML page.
   */
  private async toApiError(response: Response): Promise<ApiError> {
    let payload: ApiErrorPayload | null = null
    try {
      const parsed: unknown = await response.json()
      if (typeof parsed === 'object' && parsed !== null) {
        const candidate = parsed as Partial<ApiErrorPayload>
        if (typeof candidate.code === 'string' && typeof candidate.message === 'string') {
          payload = candidate as ApiErrorPayload
        }
      }
    } catch {
      payload = null
    }

    if (payload) {
      return new ApiError(payload.message, payload.code, response.status, payload.errors)
    }
    return new ApiError(GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', response.status)
  }
}

export const apiClient = new ApiClient()
