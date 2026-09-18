const { chromium } = require('playwright')

const BASE = 'http://localhost:5173/settings'

/** 只走元素树（忽略文本），输出带层级的结构签名。 */
const STRUCTURE = (el) => {
  const out = []
  const walk = (node, depth) => {
    const dt = node.getAttribute('data-test')
    const cls = node.getAttribute('class')
    out.push(`${'  '.repeat(depth)}<${node.tagName.toLowerCase()}`
      + `${dt ? ` data-test=${dt}` : ''}${cls ? ` class="${cls}"` : ''}>`)
    for (const child of node.children) walk(child, depth + 1)
  }
  walk(el, 0)
  return out
}

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } })
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  const capture = async (tabId, cardTestId) => {
    await page.locator(`[data-test="provider-tab-${tabId}"]`).click()
    await page.waitForTimeout(1000)
    const card = page.locator(`[data-test="${cardTestId}"]`)
    await card.waitFor({ state: 'visible', timeout: 5000 })
    return card.evaluate(STRUCTURE)
  }

  const oc = await capture('opencode_zen', 'opencode-card')
  const or = await capture('openrouter', 'openrouter-card')

  console.log(`opencode nodes: ${oc.length}   openrouter nodes: ${or.length}`)
  console.log('--- structural diff (opencode vs openrouter) ---')
  const max = Math.max(oc.length, or.length)
  let diffs = 0
  for (let i = 0; i < max; i += 1) {
    const left = (oc[i] ?? '(none)').replace(/ data-test=[^ ]*/, '').replace(/opencode/g, 'X')
    const right = (or[i] ?? '(none)').replace(/ data-test=[^ ]*/, '').replace(/openrouter/g, 'X')
    if (left !== right) {
      diffs += 1
      console.log(`#${i}\n  OC: ${oc[i] ?? '(none)'}\n  OR: ${or[i] ?? '(none)'}`)
    }
  }
  console.log(`--- differing nodes: ${diffs} ---`)

  await browser.close()
})().catch((e) => { console.error('FAILED', e); process.exit(1) })
