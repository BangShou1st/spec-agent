// Smoke-check for the assistant toggle icon: renders the shipped pill markup
// and writes a PNG so the glyph centring can be judged on pixels, not on
// the path data.
const path = require('path')
const { chromium } = require(path.join('E:/project/spec-agent/frontend', 'node_modules', 'playwright'))

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1500, height: 620 }, deviceScaleFactor: 2 })
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e)))
  await page.goto('file:///E:/project/spec-agent/frontend/.impeccable/ga-toggle-icon-check.html')
  await page.waitForTimeout(250)
  await page.screenshot({ path: 'E:/project/spec-agent/frontend/.impeccable/ga-toggle-icon-check.png', fullPage: true })
  console.log('errors=' + JSON.stringify(errors))
  await browser.close()
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
