// 文件名:graphUndo.ts
// 用途:画布撤销/重做领域逻辑:操作日志驱动的 undo/redo 命令与可用性读取(让按钮状态诚实),从 workspaceStore 拆出,store 实例作为首参传入。
/*
 * Graph 撤销/重做领域:操作日志驱动的 undo/redo 命令,以及保证按钮状态
 * 诚实的可用性读取。
 *
 * 每个函数都是从 `workspaceStore.ts` 原样搬出的 action 主体,只是把
 * `this` 换成了作为第一个参数传入的 store 实例。store 保留原 action 名并
 * 委托到这里,调用方零改动。
 *
 * 异步响应都会与开始时捕获的项目会话校验:一次慢的 undo/redo/可用性读取
 * 绝不能把反馈、错误或可用性状态写进另一个项目纪元。
 */
import { toDisplayError } from '@/shared/http/displayError'
import {
  getUndoRedoAvailability,
  redoGraphOperation,
  undoGraphOperation,
} from '@/features/workspace/api/graphCommands'
import type { GraphUndoSlice } from './slices'

/** 从操作日志刷新撤销/重做可用性。 */
export async function refreshUndoRedoAvailabilityAction(store: GraphUndoSlice): Promise<void> {
  const projectId = store.projectId
  const session = store.projectSessionId
  if (!projectId) return
  try {
    const availability = await getUndoRedoAvailability(projectId)
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    store.undoRedo = availability
  } catch {
    // 可用性只是 UI 提示;失败时保留上一次状态。
  }
}

/** 通过操作特定的补偿实现撤销;绝不破坏性执行。 */
export async function undoGraphAction(store: GraphUndoSlice): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  store.graphCommandPending = true
  store.error = null
  try {
    const result = await undoGraphOperation(projectId)
    if (!isCurrent()) return false
    // 后端能识别时点名被补偿的节点:一次 undo 可能回滚 agent 刚产出的
    // 节点,光秃秃的操作描述("创建草稿节点")让用户无从对应。
    store.feedback = result.targetTitle
      ? `已撤销「${result.targetTitle}」`
      : result.description
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    await store.refreshUndoRedoAvailability()
    return false
  } finally {
    // 只有持有会话的一方释放锁:过期 undo 的清理绝不能释放新会话的
    // graph-command 锁(beginProject 在切换时已经重置过它)。
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/** 仅在前置条件仍满足时重做。 */
export async function redoGraphAction(store: GraphUndoSlice): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  store.graphCommandPending = true
  store.error = null
  try {
    const result = await redoGraphOperation(projectId)
    if (!isCurrent()) return false
    store.feedback = result.description
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    await store.refreshUndoRedoAvailability()
    return false
  } finally {
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}
