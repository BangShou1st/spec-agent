import { onUnmounted, watch, type Ref } from 'vue'

/**
 * Adds a window-level Escape handler while `active` is true.
 *
 * The canonical way to close overlays on Escape; UiDialogShell uses the same
 * window-level wiring. Mixed-key handlers (arrow-key menus, zoom shortcuts)
 * keep their own listeners instead of forcing this shape.
 */
export function useEscClose(
  active: Ref<boolean>,
  onClose: () => void,
): void {
  const onKey = (e: KeyboardEvent): void => {
    if (e.key === 'Escape') onClose()
  }
  watch(active, (value) => {
    if (value) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
  }, { immediate: true })
  onUnmounted(() => window.removeEventListener('keydown', onKey))
}
