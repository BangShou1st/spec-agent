/** 阅读器里画布内容（图片 / PDF）的尺寸计算。抽成纯函数便于单测几何规则。 */

export interface Size {
  w: number
  h: number
}

/**
 * 图片在阅读器里的显示尺寸。
 *
 * 基准 = **适应窗口但不放大**（自然尺寸更小时按原样显示，更大时缩到窗口内），
 * 再乘以用户的缩放倍数。这样：
 * - 100% 永远是「完整看到整张图」，不会把一张小图糊成满屏；
 * - 放大倍数对任何尺寸的图都真实生效 —— 这才是「内部放大」。
 *
 * 反例（也是被换掉的旧做法）：只给 `max-width: 100%` 再按倍数放大容器。
 * `max-width` 是上限不是基数，图片小于窗口时会被自然尺寸卡住，
 * 于是倍数涨、图不动，控件等于撒谎。
 */
export function readerImageSize(natural: Size, box: Size, zoom: number): Size {
  const fit = Math.min(1, box.w / natural.w, box.h / natural.h)
  return { w: natural.w * fit * zoom, h: natural.h * fit * zoom }
}

/** 画布盒子（iframe）没有自然尺寸，直接按窗口基准乘倍数，让 PDF 视图放大重排。 */
export function readerCanvasSize(box: Size, zoom: number): Size {
  return { w: box.w * zoom, h: box.h * zoom }
}
