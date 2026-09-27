// 文件名:origin.ts
// 用途:URL 来源(scheme/host/有效端口)比较,供凭据复用守卫使用。
//       与后端 ProviderUrlSecurity.sameOrigin 的语义保持一致:
//       同一来源才允许自动复用已存密钥;默认端口(http 80 / https 443)
//       归一化;解析失败的输入一律视为"不同来源"(保守方向)。

const DEFAULT_PORTS: Record<string, number> = { http: 80, https: 443 }

export function originOf(rawUrl: string): string | null {
  const raw = rawUrl.trim()
  if (!raw) {
    return null
  }
  try {
    const url = new URL(raw)
    const scheme = url.protocol.replace(/:$/, '').toLowerCase()
    const defaultPort = DEFAULT_PORTS[scheme]
    if (!url.hostname || defaultPort === undefined) {
      return null
    }
    const port = url.port ? Number(url.port) : defaultPort
    return `${scheme}://${url.hostname.toLowerCase()}:${port}`
  } catch {
    return null
  }
}

export function sameOrigin(a: string, b: string): boolean {
  const originA = originOf(a)
  return originA !== null && originA === originOf(b)
}
