// 文件名:useDialogForm.ts
// 用途:对话框表单复用 composable:在弹窗打开时重置本地草稿状态,关闭时恢复初始值。
import { watch, type Ref } from 'vue'

/**
 * 对话框表单的通用接线：每次打开时重置本地状态。
 *
 * 同一 `watch(() => props.open, ...)` 模式曾在 8 个弹窗组件里重复；
 * 关闭时的清理（如有）由各组件自己的 `watch`/`onUnmounted` 处理，
 * 这里只负责"打开即重置"这一件事。
 */
export function useDialogReset(open: Ref<boolean>, reset: () => void): void {
  watch(open, (value) => {
    if (value) reset()
  }, { immediate: true })
}
