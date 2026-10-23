import { mkdirSync } from 'node:fs'
import path from 'node:path'
import type { Route } from '@playwright/test'
import { test, expect, type Page } from './helpers'

/**
 * Provider 设置的截图与行为验收。
 *
 * 产品现状(Provider 注册表 V2):内置只有 OpenCode Zen 与 Kilo Gateway 两张卡;
 * 任意多个自定义连接通过末尾的「＋」弹窗创建,并各自成为一枚 Tab。
 * OpenRouter 不再是内置提供商——如果它出现,只能是一条普通的自定义连接。
 * 因此本用例既产出截图,也把「没有内置 OpenRouter 入口」「多个自定义连接共存」
 * 「创建 / 编辑 / 切换 / 删除」这些产品行为断言成真实通过,而不是靠删掉用例来消红。
 *
 * 截图产物写入"每次运行独立"的目录:避免固定路径下的陈旧/被占用文件
 * 让 PNG 落盘出现 UNKNOWN 错误,也让重复执行互不覆盖。需要固定位置时
 * 用 PLAYWRIGHT_SHOTS_DIR 覆盖。
 */
const SHOTS = process.env.PLAYWRIGHT_SHOTS_DIR
  ?? path.join('test-results', 'provider-shots',
    new Date().toISOString().replace(/[:.]/g, '-'))
test.beforeAll(() => {
  mkdirSync(SHOTS, { recursive: true })
})
test.afterAll(async () => {
  test.info().annotations.push({ type: 'shots-dir', description: SHOTS })
})

test.setTimeout(240_000)

/** 线级契约:ProviderView(见 backend ModelProviderViewService)。 */
interface ProviderRow {
  id: string
  preset: 'OPENCODE_ZEN' | 'KILO' | 'CUSTOM'
  displayName: string
  apiFormat: string
  baseUrl: string
  endpointPreview: string | null
  hasKey: boolean
  maskedKey: string | null
  selectedModel: string
  manualModel: boolean
  configured: boolean
  configRevision: number
  validated: boolean
  active: boolean
  capabilities: {
    userNamed: boolean
    selectableFormat: boolean
    editableBaseUrl: boolean
    requiresApiKey: boolean
    builtInCatalog: boolean
  }
  verifiedCapabilities: Record<string, string>
}

const CUSTOM_CAPABILITIES = {
  userNamed: true, selectableFormat: true, editableBaseUrl: true,
  requiresApiKey: false, builtInCatalog: false,
}

function customRow(over: Partial<ProviderRow> & Pick<ProviderRow, 'id' | 'displayName'>): ProviderRow {
  return {
    preset: 'CUSTOM',
    apiFormat: 'CHAT_COMPLETIONS',
    baseUrl: 'https://api.example.com/v1',
    endpointPreview: null,
    hasKey: false,
    maskedKey: null,
    selectedModel: '',
    manualModel: true,
    configured: false,
    configRevision: 0,
    validated: false,
    active: false,
    capabilities: CUSTOM_CAPABILITIES,
    verifiedCapabilities: {},
    ...over,
  }
}

/**
 * 有状态的假 Provider 注册表:每次写入都反映到后续 GET,截图才代表真实流转。
 * 返回 `reset()`,让每个视口从同一份干净状态开始——否则第二个视口会继承
 * 上一个视口创建的连接,断言就不再是同一件事。
 */
async function mockProviderRegistry(page: Page) {
  const custom: ProviderRow[] = []
  let activeProvider: 'OPENCODE_ZEN' | 'KILO' | 'CUSTOM' = 'OPENCODE_ZEN'
  let activeProviderId: string | null = null
  let nextId = 1

  const opencodeStatus = { configured: true, maskedKey: '••••zen1', selectedModel: 'zen-model-free' }
  const kiloStatus = { configured: false, maskedKey: null as string | null, selectedModel: null as string | null }
  const kiloModels = ['kilo-free-a', 'kilo-paid-b']

  function reset() {
    custom.length = 0
    activeProvider = 'OPENCODE_ZEN'
    activeProviderId = null
    nextId = 1
  }

  // 每条处理器自己按路径分派:不依赖 Playwright 的路由注册顺序(后注册者先匹配),
  // 也就不会因为某天调整了注册次序而悄悄把 /models 换成设置状态。
  await page.route('**/api/v1/settings/opencode**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    const body = pathname.endsWith('/models')
      ? { allModels: ['zen-model-free'], freeModels: ['zen-model-free'] }
      : opencodeStatus
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.route('**/api/v1/settings/kilo**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    const body = pathname.endsWith('/models')
      ? { allModels: kiloModels, freeModels: kiloModels.filter(m => m.includes('free')) }
      : kiloStatus
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  const listView = () => ({
    // 预设行由后端合成;前端只用它取「当前使用」标签,真正的 Tab 来自静态预设表。
    providers: [
      {
        id: 'OPENCODE_ZEN', preset: 'OPENCODE_ZEN', displayName: 'OpenCode Zen',
        apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'https://opencode.ai/inference/v1', endpointPreview: null,
        hasKey: true, maskedKey: '••••zen1', selectedModel: 'zen-model-free', manualModel: false,
        configured: true, configRevision: 0, validated: false,
        active: activeProviderId === null && activeProvider === 'OPENCODE_ZEN',
        capabilities: { userNamed: false, selectableFormat: false, editableBaseUrl: false, requiresApiKey: false, builtInCatalog: true },
        verifiedCapabilities: {},
      },
      {
        id: 'KILO', preset: 'KILO', displayName: 'Kilo Gateway',
        apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'https://ai-gateway.kilo.ai/api/v1', endpointPreview: null,
        hasKey: false, maskedKey: null, selectedModel: null, manualModel: false,
        configured: false, configRevision: 0, validated: false,
        active: activeProviderId === null && activeProvider === 'KILO',
        capabilities: { userNamed: false, selectableFormat: false, editableBaseUrl: false, requiresApiKey: false, builtInCatalog: true },
        verifiedCapabilities: {},
      },
      ...custom.map(row => ({ ...row, active: activeProviderId === row.id })),
    ],
    activeProvider,
    activeProviderId,
  })
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

  await page.route('**/api/v1/settings/providers**', async (route) => {
    const request = route.request()
    const method = request.method()
    const url = new URL(request.url())
    const rest = url.pathname.replace('/api/v1/settings/providers', '') || ''
    const body = () => (request.postDataJSON() ?? {}) as Record<string, string | undefined>

    if (method === 'GET' && rest === '') return json(route, 200, listView())
    if (method === 'GET' && rest === '/active') {
      return json(route, 200, { activeProvider, activeProviderId })
    }
    if (method === 'POST' && rest === '/discover') {
      return json(route, 200, {
        models: ['custom-model-a', 'openrouter/auto', 'custom-model-with-a-very-long-id-that-tests-ellipsis-behavior-in-dropdowns'],
        freeModels: [], manualModel: false,
        endpointPreview: `${body().baseUrl ?? ''}/chat/completions`,
      })
    }
    if (method === 'POST' && rest === '/activate') {
      // 预设激活端点(本用例只经由行激活,保留契约完整性)。
      activeProvider = (body().provider as typeof activeProvider) ?? 'OPENCODE_ZEN'
      activeProviderId = null
      return json(route, 200, listView())
    }
    if (method === 'POST' && rest === '') {
      const patch = body()
      const row = customRow({
        id: `custom-${nextId++}`,
        displayName: patch.displayName ?? 'Custom',
        apiFormat: patch.apiFormat ?? 'CHAT_COMPLETIONS',
        baseUrl: patch.baseUrl ?? '',
        endpointPreview: patch.baseUrl ? `${patch.baseUrl}/chat/completions` : null,
        hasKey: Boolean(patch.apiKey), maskedKey: patch.apiKey ? '••••0000' : null,
        selectedModel: patch.selectedModel ?? '',
        configured: true, configRevision: 1,
      })
      custom.push(row)
      return json(route, 200, row)
    }

    const match = rest.match(/^\/([^/]+)(\/.*)?$/)
    if (!match) return route.fallback()
    const row = custom.find(r => r.id === match[1])
    if (!row) return json(route, 404, { code: 'MODEL_PROVIDER_NOT_FOUND', message: 'unknown provider' })
    const tail = match[2] ?? ''

    if (method === 'PUT' && tail === '') {
      const patch = body()
      row.displayName = patch.displayName ?? row.displayName
      row.apiFormat = patch.apiFormat ?? row.apiFormat
      row.baseUrl = patch.baseUrl ?? row.baseUrl
      row.selectedModel = patch.selectedModel ?? row.selectedModel
      if (patch.apiKey) { row.hasKey = true; row.maskedKey = '••••0000' }
      row.configRevision += 1
      // 改配置即失去当前验证:能力证明只对当前配置修订号有效。
      row.validated = false
      row.verifiedCapabilities = {}
      return json(route, 200, row)
    }
    if (method === 'DELETE' && tail === '') {
      custom.splice(custom.indexOf(row), 1)
      if (activeProviderId === row.id) { activeProvider = 'OPENCODE_ZEN'; activeProviderId = null }
      return route.fulfill({ status: 204, body: '' })
    }
    if (method === 'POST' && tail === '/validate') {
      row.validated = true
      row.verifiedCapabilities = { CHAT: 'VERIFIED', STRUCTURED: 'VERIFIED', NATIVE_TOOLS: 'VERIFIED' }
      return json(route, 200, row)
    }
    if (method === 'POST' && tail === '/activate') {
      activeProvider = 'CUSTOM'
      activeProviderId = row.id
      return json(route, 200, listView())
    }
    if (method === 'POST' && tail === '/probe') {
      return json(route, 200, { models: ['custom-model-a'], freeModels: [], manualModel: false, endpointPreview: null })
    }
    if (method === 'GET' && tail === '/models') {
      return json(route, 200, { models: [row.selectedModel], freeModels: [], manualModel: true, endpointPreview: null })
    }
    return route.fallback()
  })

  return { reset }
}

test.describe('provider settings screenshots and behaviour', () => {
  test('capture all required states', async ({ page }) => {
    const registry = await mockProviderRegistry(page)

    const viewports = [
      { width: 1440, height: 900 },
      { width: 1366, height: 768 },
    ]
    for (const vp of viewports) {
      registry.reset()
      await page.setViewportSize(vp)
      const suffix = vp.width + 'x' + vp.height

      // ── 两个内置提供商:没有 OpenRouter 内置入口 ──
      await page.goto('/settings')
      await expect(page.getByTestId('settings-page')).toBeVisible()
      await expect(page.getByTestId('active-provider-name')).toContainText('OpenCode Zen')
      await expect(page.getByTestId('opencode-card')).toBeVisible()
      await expect(page.getByTestId('provider-tab-openrouter')).toHaveCount(0)
      await expect(page.getByTestId('openrouter-card')).toHaveCount(0)
      await page.screenshot({ path: SHOTS + '/settings-opencode-active-' + suffix + '.png', fullPage: true })

      await page.getByTestId('provider-tab-kilo').click()
      await expect(page.getByTestId('kilo-card')).toBeVisible()
      await page.screenshot({ path: SHOTS + '/settings-kilo-' + suffix + '.png', fullPage: true })

      // ── 通过「＋」新建第一个 Custom ──
      await page.getByTestId('provider-add-custom').click()
      await expect(page.getByTestId('connection-dialog')).toBeVisible()
      await page.getByTestId('connection-name').fill('Local Gateway')
      await page.getByTestId('connection-url').fill('http://localhost:11434/v1')
      await page.getByTestId('connection-discover').click()
      await page.getByTestId('connection-model').fill('custom-model-a')
      await page.screenshot({ path: SHOTS + '/settings-custom-create-' + suffix + '.png', fullPage: true })
      await page.getByTestId('connection-save').click()
      await expect(page.getByTestId('connection-dialog')).toHaveCount(0)
      await expect(page.getByTestId('connection-panel')).toBeVisible()
      await expect(page.getByTestId('custom-current-model')).toContainText('custom-model-a')
      // 刚保存但未验证:状态必须是「已配置但未验证」,且激活按钮不可用。
      await expect(page.getByTestId('custom-state')).toContainText('已配置但未验证')
      await expect(page.getByTestId('connection-activate')).toBeDisabled()
      await page.screenshot({ path: SHOTS + '/settings-custom-configured-' + suffix + '.png', fullPage: true })

      // ── 第二个 Custom(OpenRouter 只是普通自定义连接,不是内置入口)──
      await page.getByTestId('provider-add-custom').click()
      await expect(page.getByTestId('connection-dialog')).toBeVisible()
      await page.getByTestId('connection-name').fill('OpenRouter')
      await page.getByTestId('connection-url').fill('https://openrouter.ai/api/v1')
      await page.getByTestId('connection-key').fill('sk-or-v1-mock')
      await page.getByTestId('connection-discover').click()
      await page.getByTestId('connection-model').fill('openrouter/auto')
      await page.getByTestId('connection-save').click()
      await expect(page.getByTestId('connection-dialog')).toHaveCount(0)

      // 多个自定义连接必须共存为独立的 Tab。
      const tabs = page.getByTestId('provider-tabs')
      await expect(tabs.getByRole('tab', { name: /Local Gateway/ })).toBeVisible()
      await expect(tabs.getByRole('tab', { name: /OpenRouter/ })).toBeVisible()
      await expect(page.getByTestId('custom-current-model')).toContainText('openrouter/auto')
      await page.screenshot({ path: SHOTS + '/settings-custom-multiple-' + suffix + '.png', fullPage: true })

      // ── 切换:点回第一个连接,内容跟着换 ──
      await tabs.getByRole('tab', { name: /Local Gateway/ }).click()
      await expect(page.getByTestId('custom-current-model')).toContainText('custom-model-a')

      // ── 编辑:弹窗预填当前值,改完保存生效 ──
      await page.getByTestId('custom-edit').click()
      await expect(page.getByTestId('connection-dialog')).toBeVisible()
      await expect(page.getByTestId('connection-name')).toHaveValue('Local Gateway')
      await page.getByTestId('connection-name').fill('Local Gateway Renamed')
      await page.getByTestId('connection-save').click()
      await expect(page.getByTestId('connection-dialog')).toHaveCount(0)
      await expect(tabs.getByRole('tab', { name: /Local Gateway Renamed/ })).toBeVisible()

      // ── 验证 + 激活:只有拿到当前修订号的能力证明才允许激活 ──
      await page.getByTestId('connection-validate').click()
      await expect(page.getByTestId('custom-state')).toContainText('配置有效')
      await page.getByTestId('connection-activate').click()
      await expect(page.getByTestId('active-provider-name')).toContainText('Local Gateway Renamed')
      await expect(page.getByTestId('custom-state')).toContainText('当前使用')
      await page.screenshot({ path: SHOTS + '/settings-custom-active-' + suffix + '.png', fullPage: true })

      // ── 删除:当前使用的连接不可删除,切走后才可删除 ──
      await expect(page.getByTestId('custom-delete')).toBeDisabled()
      await page.getByTestId('provider-tab-kilo').click()
      await expect(page.getByTestId('kilo-card')).toBeVisible()
      await tabs.getByRole('tab', { name: /OpenRouter/ }).click()
      await page.getByTestId('custom-delete').click()
      await expect(page.getByTestId('connection-delete-dialog')).toBeVisible()
      await page.screenshot({ path: SHOTS + '/settings-custom-delete-confirm-' + suffix + '.png', fullPage: true })
      await page.getByTestId('connection-delete-dialog').getByRole('button', { name: '确认删除' }).click()
      await expect(page.getByTestId('connection-delete-dialog')).toHaveCount(0)
      await expect(tabs.getByRole('tab', { name: /OpenRouter/ })).toHaveCount(0)
      // 剩下的自定义连接不受影响。
      await expect(tabs.getByRole('tab', { name: /Local Gateway Renamed/ })).toBeVisible()
    }
  })
})
