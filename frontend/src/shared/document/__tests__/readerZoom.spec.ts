import { describe, expect, it } from 'vitest'
import { readerCanvasSize, readerImageSize } from '@/shared/document/readerZoom'

describe('reader image sizing', () => {
  const box = { w: 1000, h: 600 }

  it('shrinks an oversized image to fit the window at 100%', () => {
    expect(readerImageSize({ w: 2000, h: 1000 }, box, 1)).toEqual({ w: 1000, h: 500 })
  })

  it('never enlarges an image that is already smaller than the window', () => {
    // 100% 是「完整看到整张图」，不是「铺满窗口」—— 小图不该被糊成满屏。
    expect(readerImageSize({ w: 400, h: 200 }, box, 1)).toEqual({ w: 400, h: 200 })
  })

  it('magnifies a small image proportionally once the user zooms in', () => {
    // 旧实现只给 max-width: 100% 再放大容器，`max-width` 是上限不是基数，
    // 小图会被自然尺寸卡住 —— 倍数涨、图不动。这条就是防它回退。
    expect(readerImageSize({ w: 400, h: 200 }, box, 2)).toEqual({ w: 800, h: 400 })
    expect(readerImageSize({ w: 400, h: 200 }, box, 3)).toEqual({ w: 1200, h: 600 })
  })

  it('magnifies a large image from its fitted size, not from its natural size', () => {
    // 2000 宽的图在 1000 宽的窗口里基准是 1000；200% 是 2000，而不是 4000。
    expect(readerImageSize({ w: 2000, h: 1000 }, box, 2)).toEqual({ w: 2000, h: 1000 })
  })

  it('respects the height constraint when the image is portrait', () => {
    expect(readerImageSize({ w: 600, h: 1200 }, box, 1)).toEqual({ w: 300, h: 600 })
  })

  it('scales the pdf frame straight from the window box', () => {
    expect(readerCanvasSize(box, 1)).toEqual({ w: 1000, h: 600 })
    expect(readerCanvasSize(box, 1.5)).toEqual({ w: 1500, h: 900 })
  })
})
