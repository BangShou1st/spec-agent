/**
 * Graph undo/redo domain: the operation-log driven undo/redo commands and the
 * availability read that keeps their buttons honest.
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { toDisplayError } from '@/api/displayError'
import {
  getUndoRedoAvailability,
  redoGraphOperation,
  undoGraphOperation,
} from '@/api/graphCommands'
import type { WorkspaceStore } from '../workspaceStore'

/** Refreshes Undo/Redo availability from the operation log. */
export async function refreshUndoRedoAvailabilityAction(store: WorkspaceStore): Promise<void> {
  if (!store.projectId) return
  try {
    store.undoRedo = await getUndoRedoAvailability(store.projectId)
  } catch {
    // Availability is a UI affordance; failures keep the last state.
  }
}

/** Undo via operation-specific compensation; never destructive. */
export async function undoGraphAction(store: WorkspaceStore): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  store.graphCommandPending = true
  store.error = null
  try {
    const result = await undoGraphOperation(store.projectId)
    // Name the compensated node when the backend can identify it: an undo
    // may roll back a node the agent just produced, which the bare
    // operation description ("创建草稿节点") does not let the user recognize.
    store.feedback = result.targetTitle
      ? `已撤销「${result.targetTitle}」`
      : result.description
    await store.refreshWorkspace()
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    await store.refreshUndoRedoAvailability()
    return false
  } finally {
    store.graphCommandPending = false
  }
}

/** Redo only while preconditions still hold. */
export async function redoGraphAction(store: WorkspaceStore): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  store.graphCommandPending = true
  store.error = null
  try {
    const result = await redoGraphOperation(store.projectId)
    store.feedback = result.description
    await store.refreshWorkspace()
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    await store.refreshUndoRedoAvailability()
    return false
  } finally {
    store.graphCommandPending = false
  }
}
