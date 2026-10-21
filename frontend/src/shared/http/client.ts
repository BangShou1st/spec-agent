// 文件名:client.ts
// 用途:Phase 6 API 的统一类型化 HTTP 客户端与 ApiError 类型:每个响应都按稳定的 API 错误契约解析,不匹配契约的内容一律回退为通用文案,绝不泄漏原始响应体。
import type { ApiErrorPayload, ApiFieldError } from '@/shared/contracts/types'
import { identityHeaders } from './requestIdentity'

/*
 * 呈现给 UI 的类型化 API 失败。`message` 始终可以安全渲染:它要么是后端
 * 消毒过的稳定消息,要么是通用的前端兜底文案。原始响应体、堆栈、HTML
 * 错误页与 provider 载荷绝不被暴露。
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly errors?: ApiFieldError[]
  readonly details?: Record<string, string>

  constructor(
    message: string,
    code: string,
    status: number,
    errors?: ApiFieldError[],
    details?: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.errors = errors
    this.details = details
  }
}

export const GENERIC_ERROR_MESSAGE = '操作失败，请稍后重试'

/**
 * 上游 Provider 的错误码前缀。
 *
 * 后端把提供商侧的失败冻结成一组提供商中立的错误码
 * (MODEL_PROVIDER_AUTHENTICATION / MODEL_PROVIDER_RATE_LIMITED /
 * MODEL_PROVIDER_UNREACHABLE / MODEL_PROVIDER_TIMEOUT / ...),
 * 它们描述的是「上游怎么了」,而不是「用户会话怎么了」。
 */
const UPSTREAM_PROVIDER_ERROR_PREFIX = 'MODEL_PROVIDER_'

/** 该错误码是否描述上游 Provider 的失败(而非用户会话失效)。 */
export function isUpstreamProviderError(code: string): boolean {
  return (code ?? '').trim().toUpperCase().startsWith(UPSTREAM_PROVIDER_ERROR_PREFIX)
}

/** 类型化客户端与 SSE 流共享的单一 API 基准地址。 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

/*
 * Phase 6 API 的唯一类型化 HTTP 客户端。每个响应都按稳定的 API 错误契约
 * 解析;任何不符合契约的内容都变成通用前端兜底,而不是泄漏原始响应体。
 */
class ApiClient {
  async get<T>(path: string, options?: Pick<RequestInit, 'signal'>): Promise<T> {
    return this.request<T>(path, { ...options, method: 'GET' })
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
      const headers = new Headers(init.headers)
      for (const [key, value] of Object.entries(identityHeaders(init.method))) headers.set(key, value)
      response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers, credentials: 'same-origin' })
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
    const apiError = await this.toApiError(response)
    // 只有「用户自己的会话失效」才触发会话恢复。
    // 上游 Provider 拒绝凭据同样是 401(错误码 MODEL_PROVIDER_AUTHENTICATION):
    // 那是 Provider 设置页必须展示的上游错误,绝不是用户会话过期。
    // 把它误判成会话失效会把整页重置成「正在确认账户…」,并让真正的上游
    // 错误从界面上消失。
    if (response.status === 401 && typeof window !== 'undefined'
      && !isUpstreamProviderError(apiError.code)) {
      window.dispatchEvent(new Event('spec-agent-session-expired'))
    }
    throw apiError
  }

  /*
   * 按API 错误契约({code, message, timestamp, errors})解析失败响应。
   * 无法按契约解析的响应体产出通用兜底文案,绝不是原始响应体或 HTML 页。
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
      return new ApiError(
        payload.message,
        payload.code,
        response.status,
        payload.errors,
        payload.details,
      )
    }
    return new ApiError(GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', response.status)
  }
}

export const apiClient = new ApiClient()
