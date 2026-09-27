// 文件名:timing.ts
// 用途:测试与轮询共用的时间工具:sleep 等,供轮询循环与受控时序测试使用。
/** Wait helper shared by run-polling loops and deferred UI work. */
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
