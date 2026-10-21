// 文件名:providerReachability.ts
// 用途:模型 Provider「上游可达性」的唯一语义层:把一次上游响应折叠为确定性的
//       可达性判定。HTTP 200 与 HTTP 429 都判定为「上游可达」,429 另外标记为
//       RATE_LIMITED(限流状态与错误信息保留,绝不自动重试、绝不额外消耗配额);
//       401/403 表示请求已到达上游但认证/权限检查失败;DNS/连接拒绝/TLS 失败与
//       超时各有独立判定,任何异常都绝不被当成成功。
//
// 边界(不可放宽):本判定只服务于「连通性/可达性探测」。模型实际生成、Provider
// 保存与兼容性/Schema 验证各有独立契约,绝不因为上游回了 429 就被算作成功,
// 凭据有效性也绝不因为 429 就被认定为已验证。
import { stableCode } from './errorCopy'
import { ApiError, GENERIC_ERROR_MESSAGE } from './client'

/** 上游可达性判定结果。 */
export type ProviderReachability =
  /** 上游正常应答(HTTP 2xx)。 */
  | 'REACHABLE'
  /** 上游可达但当前被限流(HTTP 429)。 */
  | 'RATE_LIMITED'
  /** 请求已到达上游,但认证检查失败(HTTP 401 / 上游拒绝凭据)。 */
  | 'AUTHENTICATION_FAILED'
  /** 请求已到达上游,但权限不足(HTTP 403)。 */
  | 'PERMISSION_DENIED'
  /** 请求从未到达上游:DNS、连接拒绝、TLS 握手失败。 */
  | 'UNREACHABLE'
  /** 上游未在时限内应答。 */
  | 'TIMEOUT'
  /** 用户自己的会话已失效:请求根本没有离开浏览器。 */
  | 'SESSION_EXPIRED'
  /** 其它 HTTP 错误:按错误分类判定,绝不一律成功。 */
  | 'FAILED'

/** 上游可达(限流也算可达)。 */
export function isProviderReachable(reachability: ProviderReachability): boolean {
  return reachability === 'REACHABLE' || reachability === 'RATE_LIMITED'
}

/**
 * 把一次上游响应折叠为可达性判定。
 *
 * 错误码优先于 HTTP 状态:同一个 401 既可能是「上游拒绝凭据」,也可能是
 * 「用户会话失效」,只有稳定的提供商中立错误码能区分二者。
 */
export function classifyProviderReachability(status: number, code = ''): ProviderReachability {
  const normalized = (code ?? '').trim().toUpperCase()
  // 用户会话失效发生在请求离开浏览器之前,绝不是上游的判定。
  if (normalized === 'UNAUTHENTICATED') return 'SESSION_EXPIRED'
  if (status >= 200 && status < 300) return 'REACHABLE'
  switch (stableCode(normalized)) {
    case 'RATE_LIMITED':
      return 'RATE_LIMITED'
    case 'AUTHENTICATION':
      return 'AUTHENTICATION_FAILED'
    case 'CONNECTION':
    case 'NETWORK_ERROR':
      return 'UNREACHABLE'
    case 'TIMEOUT':
      return 'TIMEOUT'
    default:
      break
  }
  // 没有提供商错误码时的状态兜底。
  if (status === 429) return 'RATE_LIMITED'
  if (status === 401) return 'AUTHENTICATION_FAILED'
  if (status === 403) return 'PERMISSION_DENIED'
  if (status === 504) return 'TIMEOUT'
  if (status === 0) return 'UNREACHABLE'
  return 'FAILED'
}

/** 把一次失败的探测折叠为「可达性 + 展示文案」。 */
export interface ProviderProbeFailure {
  /** 稳定错误码:沿用后端契约,绝不替换成前端自造码。 */
  code: string
  /** 展示给用户的消息。 */
  message: string
  /** 上游是否可达(被限流也算可达)。 */
  reachable: boolean
  /** 是否被上游限流。 */
  rateLimited: boolean
  /** 可达性分类。 */
  reachability: ProviderReachability
}

const RATE_LIMITED_MESSAGE = '上游可达，但当前被限流，请稍后重试'
const AUTHENTICATION_MESSAGE = '上游拒绝了认证，请检查 API Key'
const PERMISSION_MESSAGE = '上游拒绝了访问权限'
const UNREACHABLE_MESSAGE = '无法连接到上游服务'
const TIMEOUT_MESSAGE = '上游服务响应超时'
const SESSION_MESSAGE = '登录状态已失效，请重新登录'

/**
 * 折叠一次探测失败。`message` 由可达性分类决定,让界面能明确区分
 * 「上游不可达」与「上游可达但被限流」。
 */
export function classifyProviderProbeFailure(err: unknown): ProviderProbeFailure {
  const status = err instanceof ApiError ? err.status : 0
  const code = err instanceof ApiError ? err.code : 'UNKNOWN_ERROR'
  const reachability = classifyProviderReachability(status, code)
  const reachable = isProviderReachable(reachability)
  let message: string
  switch (reachability) {
    case 'RATE_LIMITED':
      message = RATE_LIMITED_MESSAGE
      break
    case 'AUTHENTICATION_FAILED':
      message = AUTHENTICATION_MESSAGE
      break
    case 'PERMISSION_DENIED':
      message = PERMISSION_MESSAGE
      break
    case 'UNREACHABLE':
      message = UNREACHABLE_MESSAGE
      break
    case 'TIMEOUT':
      message = TIMEOUT_MESSAGE
      break
    case 'SESSION_EXPIRED':
      message = SESSION_MESSAGE
      break
    default:
      // 其它错误沿用后端已脱敏的安全文案;绝不回显原始响应体。
      message = err instanceof ApiError ? err.message : GENERIC_ERROR_MESSAGE
      break
  }
  return { code, message, reachable, rateLimited: reachability === 'RATE_LIMITED', reachability }
}
