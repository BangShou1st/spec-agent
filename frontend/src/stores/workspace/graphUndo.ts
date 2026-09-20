/**
 * Graph undo/redo domain: the operation-log driven undo/redo commands and the
 * availability read that keeps their buttons honest.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 *
 * Async responses are validated against the project session captured at
 * start: a slow undo/redo/availability read must never write feedback,
 * error, or availability state into a different project era.
 */
import { toDisplayError } from '@/api/displayError'
import {
  getUndoRedoAvailability,
  redoGraphOperation,
  undoGraphOperation,
} from '@/api/graphCommands'
import type { GraphUndoSlice } from './slices'

/** Refreshes Undo/Redo availability from the operation log. */
export async function refreshUndoRedoAvailabilityAction(store: GraphUndoSlice): Promise<void> {
  const projectId = store.projectId
  const session = store.projectSessionId
  if (!projectId) return
  try {
    const availability = await getUndoRedoAvailability(projectId)
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    store.undoRedo = availability
  } catch {
    // Availability is a UI affordance; failures keep the last state.
  }
}

/** Undo via operation-specific compensation; never destructive. */
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
    // Name the compensated node when the backend can identify it: an undo
    // may roll back a node the agent just produced, which the bare
    // operation description ("创建草稿节点") does not let the user recognize.
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
    // Only the owning session releases the lock: a stale undo's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/** Redo only while preconditions still hold. */
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
