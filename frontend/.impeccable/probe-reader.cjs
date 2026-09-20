/**
 * 在真实浏览器里验证阅读器缩放（靠 reader-check.html 这个沙箱页直接挂真实组件，
 * 避免为了看一眼效果往开发库里插假节点）：
 *   node .impeccable/probe-reader.cjs
 * - 流式内容（Markdown）用 CSS zoom，字号与行宽一起变；
 * - 画布内容（图片）用盒子尺寸换算，放大后能滚到边缘。
 */
const { chromium } = require('playwright')

const OUT = __dirname + '/shots'

async function run(page, kind, size) {
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const qs = new URLSearchParams({ kind, ...(size ? { size } : {}) })
  const label = size ? `${kind}@${size}` : kind
  await page.goto(`http://localhost:5173/.impeccable/reader-check.html?${qs}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-test="file-preview-dialog"]')
  await page.waitForTimeout(400)

  const body = () => page.locator('.file-preview__body')
  const measure = async () => page.evaluate(() => {
    const body = document.querySelector('.file-preview__body')
    const flow = document.querySelector('.file-preview__flow')
    const canvas = document.querySelector('.file-preview__canvas')
    const img = document.querySelector('.file-preview__image')
    const cs = (el) => (el ? getComputedStyle(el) : null)
    const firstBlock = flow?.querySelector('h1, pre, p')
    return {
      level: document.querySelector('[data-test="file-preview-zoom-level"]')?.textContent,
      flowZoom: cs(flow)?.zoom ?? cs(flow)?.webkitZoom ?? null,
      flowFontSize: firstBlock ? cs(firstBlock).fontSize : null,
      canvasW: canvas ? Math.round(canvas.getBoundingClientRect().width) : null,
      canvasH: canvas ? Math.round(canvas.getBoundingClientRect().height) : null,
      imgW: img ? Math.round(img.getBoundingClientRect().width) : null,
      imgH: img ? Math.round(img.getBoundingClientRect().height) : null,
      bodyClient: { w: body?.clientWidth, h: body?.clientHeight },
      bodyScroll: { w: body?.scrollWidth, h: body?.scrollHeight },
    }
  })

  const before = await measure()
  await page.screenshot({ path: `${OUT}/reader-${label}-100.png` })

  await page.locator('[data-test="file-preview-zoom-in"]').click()
  await page.locator('[data-test="file-preview-zoom-in"]').click()
  await page.waitForTimeout(300)
  const after = await measure()
  await page.screenshot({ path: `${OUT}/reader-${label}-156.png` })

  // 继续拉到 300%，检查正文列宽是否仍然受视口约束（不该出现横向滚动）。
  for (let i = 0; i < 5; i += 1) {
    const disabled = await page.locator('[data-test="file-preview-zoom-in"]').isDisabled()
    if (disabled) break
    await page.locator('[data-test="file-preview-zoom-in"]').click()
  }
  await page.waitForTimeout(300)
  const extreme = await measure()
  await page.screenshot({ path: `${OUT}/reader-${label}-max.png` })

  // 放大后必须能把右下角拽/滚进视野。
  await body().evaluate((el) => { el.scrollLeft = 99999; el.scrollTop = 99999 })
  const scrollReach = await page.evaluate(() => {
    const el = document.querySelector('.file-preview__body')
    return {
      left: el.scrollLeft, top: el.scrollTop,
      maxLeft: el.scrollWidth - el.clientWidth, maxTop: el.scrollHeight - el.clientHeight,
    }
  })

  console.log(`\n=== ${label} ===`)
  console.log('100% :', JSON.stringify(before))
  console.log('156% :', JSON.stringify(after))
  console.log('max  :', JSON.stringify(extreme))
  console.log('scroll:', JSON.stringify(scrollReach))
  console.log('errors:', errors.length ? errors : 'none')
}

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 } })
  await run(page, 'md')
  await run(page, 'image', '1600x1000')
  await run(page, 'image', '360x200')
  await browser.close()
})().catch((e) => { console.error('FAIL', e.message); process.exit(1) })
