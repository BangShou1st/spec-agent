// 文件名:safeStorage.ts
// 用途:防御式 localStorage 访问:存储不可用(隐私模式/配额/内嵌 webview)时静默回退,调用方把失败当作"无偏好",后端始终是权威。
/*
 * 防御式 localStorage 访问。存储可能不可用(隐私模式、配额、内嵌
 * webview);调用方把失败当作"无偏好"处理,后端保持权威。
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
    /* 存储不可用:后端保持权威 */
  }
}
