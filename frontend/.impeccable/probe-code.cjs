const { chromium } = require('playwright')
const OUT = 'E:/project/spec-agent/frontend/.impeccable/shots'
;(async () => {
  const b = await chromium.launch()
  const p = await b.newPage({ viewport: { width: 1280, height: 860 } })
  const errs = []
  p.on('pageerror', (e) => errs.push(e.message))
  await p.goto('http://localhost:5173/.impeccable/reader-check.html?kind=code', { waitUntil: 'networkidle' })
  await p.waitForSelector('[data-test="file-preview-dialog"]')
  await p.waitForTimeout(400)
  const state = () => p.evaluate(() => {
    const body = document.querySelector('.file-preview__body')
    const pre = document.querySelector('[data-test="file-preview-original"]')
    return {
      level: document.querySelector('[data-test="file-preview-zoom-level"]')?.textContent,
      wrapLabel: document.querySelector('[data-test="file-preview-wrap"]')?.textContent?.trim(),
      wrapDisabled: document.querySelector('[data-test="file-preview-wrap"]')?.disabled,
      wrappedClass: pre?.classList.contains('file-preview__text--wrapped'),
      hScrollAt100: body.scrollWidth > body.clientWidth,
    }
  })
  console.log('100%       :', JSON.stringify(await state()))
  await p.screenshot({ path: `${OUT}/reader-code-100.png` })
  await p.locator('[data-test="file-preview-wrap"]').click()
  await p.waitForTimeout(200)
  console.log('wrapped    :', JSON.stringify(await state()))
  await p.locator('[data-test="file-preview-wrap"]').click()
  await p.locator('[data-test="file-preview-zoom-in"]').click()
  await p.locator('[data-test="file-preview-zoom-in"]').click()
  await p.waitForTimeout(200)
  console.log('150% nowrap:', JSON.stringify(await state()))
  await p.screenshot({ path: `${OUT}/reader-code-150.png` })
  console.log('errors     :', errs.length ? errs : 'none')
  await b.close()
})().catch((e) => { console.error('FAIL', e.message); process.exit(1) })
