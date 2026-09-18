const { chromium } = require('playwright')

const OUT = 'E:/project/spec-agent/frontend/.impeccable/shots'

;(async () => {
  const browser = await chromium.launch()
  for (const height of [900, 768, 700, 600]) {
    const page = await browser.newPage({ viewport: { width: 1366, height } })
    await page.goto('http://localhost:5173/settings', { waitUntil: 'networkidle' })
    await page.waitForTimeout(1000)
    const add = page.locator('[data-test="provider-add-custom"]')
    if (await add.count() === 0) { console.log(`${height}: no "+" entry`); await page.close(); continue }
    await add.click()
    await page.waitForTimeout(600)

    const info = await page.evaluate(() => {
      const veil = document.querySelector('[data-test="custom-create-dialog"]')
      const card = veil?.querySelector('.ui-card')
      const actions = veil?.querySelector('.settings-dialog__footer')
      const vh = window.innerHeight
      const rect = card?.getBoundingClientRect()
      const actionsRect = actions?.getBoundingClientRect()
      return {
        veilScrollable: veil ? veil.scrollHeight > veil.clientHeight : null,
        cardHeight: rect ? Math.round(rect.height) : null,
        cardBottom: rect ? Math.round(rect.bottom) : null,
        actionsBottom: actionsRect ? Math.round(actionsRect.bottom) : null,
        viewport: vh,
        actionsFullyVisible: actionsRect ? actionsRect.bottom <= vh : null,
      }
    })
    console.log(`${height}:`, JSON.stringify(info))
    await page.screenshot({ path: `${OUT}/dialog-h${height}.png` })
    await page.close()
  }
  await browser.close()
})().catch((e) => { console.error('FAILED', e); process.exit(1) })
