const { chromium } = require('playwright')

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await page.goto('http://localhost:5173/settings', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)

  await page.locator('[data-test="provider-tab-opencode_zen"]').first().click()
  await page.waitForTimeout(500)

  const rect = (sel) => page.evaluate((s) => {
    const el = document.querySelector(s)
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
  }, sel)

  console.log('card BEFORE:', JSON.stringify(await rect('[data-test="opencode-card"]')))

  await page.locator('[data-test="provider-add-custom"]').first().click()
  await page.waitForTimeout(800)

  const cardRect = await rect('[data-test="opencode-card"]')
  const dlgRect = await rect('.ui-card')
  console.log('card AFTER :', JSON.stringify(cardRect))
  console.log('dialog     :', JSON.stringify(dlgRect))

  if (cardRect && dlgRect) {
    const ox = Math.max(0, Math.min(cardRect.x + cardRect.w, dlgRect.x + dlgRect.w) - Math.max(cardRect.x, dlgRect.x))
    const oy = Math.max(0, Math.min(cardRect.y + cardRect.h, dlgRect.y + dlgRect.h) - Math.max(cardRect.y, dlgRect.y))
    const ratio = (ox * oy) / (cardRect.w * cardRect.h)
    console.log('overlapRatio  :', ratio.toFixed(3))
    console.log('exposedRatio  :', (1 - ratio).toFixed(3))
  }

  const samples = await page.evaluate(() => {
    const card = document.querySelector('[data-test="opencode-card"]')
    const dlg = document.querySelector('.ui-card')
    const cr = card.getBoundingClientRect()
    const out = []
    for (let fx = 0.1; fx <= 0.91; fx += 0.2) {
      const px = cr.left + cr.width * fx
      const py = cr.top + cr.height * 0.5
      const el = document.elementFromPoint(px, py)
      out.push({
        x: Math.round(px),
        top: el ? String(el.className).split(' ')[0] : null,
        inDialog: !!(el && dlg.contains(el)),
        inCard: !!(el && card.contains(el)),
      })
    }
    return out
  })
  console.log('samples       :', JSON.stringify(samples))
  await page.screenshot({ path: __dirname + '/shots/veil-open-1440.png' })
  await browser.close()
})().catch((e) => { console.error('FAIL', e.message); process.exit(1) })
