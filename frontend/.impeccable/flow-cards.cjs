const { chromium } = require('playwright')

const BASE = 'http://localhost:5173/settings'

async function probe(page, tabId, cardTestId, changeKeyTestId, modelTestId, refreshTestId) {
  await page.locator(`[data-test="provider-tab-${tabId}"]`).click()
  await page.waitForTimeout(900)
  await page.locator(`[data-test="${cardTestId}"]`).waitFor({ state: 'visible' })

  const before = await page.locator(`[data-test="${modelTestId}"]`).evaluate((el) => ({
    options: el.options.length,
    value: el.value,
    disabled: el.disabled,
  }))

  await page.locator(`[data-test="${changeKeyTestId}"]`).click()
  await page.waitForTimeout(700)

  const after = await page.locator(`[data-test="${modelTestId}"]`).evaluate((el) => ({
    options: el.options.length,
    value: el.value,
    disabled: el.disabled,
  }))
  const refreshVisible = await page.locator(`[data-test="${refreshTestId}"]`).count()
  const keyInput = await page.locator(`[data-test="${cardTestId}"] input[type="password"]`).count()

  return { before, after, refreshVisible, keyInput }
}

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } })
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  const oc = await probe(page, 'opencode_zen', 'opencode-card', 'opencode-change-key', 'opencode-model', 'opencode-refresh')
  console.log('OPENCODE  ', JSON.stringify(oc))

  const or = await probe(page, 'openrouter', 'openrouter-card', 'openrouter-change-key', 'openrouter-model', 'openrouter-refresh')
  console.log('OPENROUTER', JSON.stringify(or))

  await browser.close()
})().catch((e) => { console.error('FAILED', e); process.exit(1) })
