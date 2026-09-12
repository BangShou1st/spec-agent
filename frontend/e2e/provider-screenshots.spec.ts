import { test, expect } from '@playwright/test'

const SHOTS = '.impeccable/shots'

test.describe('provider settings screenshots', () => {
  test('capture all required states', async ({ page }) => {
    const state = {
      active: 'OPENCODE_ZEN',
      openrouter: { configured: false, maskedKey: null as string | null, selectedModel: null as string | null, configRevision: 0, validated: false },
      openrouterModels: [] as string[],
      custom: { configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null as string | null, hasKey: false, maskedKey: null as string | null, selectedModel: '', configRevision: 0, validated: false },
      customDiscover: { models: [] as string[], manualModel: false, endpointPreview: null as string | null, failAuth: false },
    }
    let statusManual = false
    const statusBody = () => ({ ...state.custom, manualModel: statusManual })

    await page.route('**/api/v1/settings/providers/active', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ activeProvider: state.active }) })
    })
    await page.route('**/api/v1/settings/opencode', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ configured: true, maskedKey: '••••zen1', selectedModel: 'zen-model-free' }) })
    })
    await page.route('**/api/v1/settings/opencode/models', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ freeModels: ['zen-model-free'] }) })
    })
    await page.route('**/api/v1/settings/openrouter', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...state.openrouter, active: state.active === 'OPENROUTER' }) })
    })
    await page.route('**/api/v1/settings/openrouter/probe', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ freeModels: state.openrouterModels }) })
    })
    await page.route('**/api/v1/settings/openrouter/models', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ freeModels: state.openrouterModels }) })
    })
    await page.route('**/api/v1/settings/openrouter/validate', async (route) => {
      state.openrouter.validated = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...state.openrouter, active: state.active === 'OPENROUTER' }) })
    })
    await page.route('**/api/v1/settings/custom', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statusBody()) })
      } else {
        state.custom.configured = true
        state.custom.validated = false
        statusManual = state.customDiscover.manualModel
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statusBody()) })
      }
    })
    await page.route('**/api/v1/settings/custom/discover', async (route) => {
      if (state.customDiscover.failAuth) {
        await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'MODEL_PROVIDER_AUTHENTICATION', message: 'The model provider rejected the request configuration' }) })
        return
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ models: state.customDiscover.models, manualModel: state.customDiscover.manualModel, endpointPreview: state.customDiscover.endpointPreview }) })
    })
    await page.route('**/api/v1/settings/custom/validate', async (route) => {
      state.custom.validated = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statusBody()) })
    })

    const viewports = [
      { width: 1440, height: 900 },
      { width: 1366, height: 768 },
    ]
    for (const vp of viewports) {
      await page.setViewportSize(vp)
      const suffix = vp.width + 'x' + vp.height

      state.active = 'OPENCODE_ZEN'
      state.openrouter = { configured: false, maskedKey: null, selectedModel: null, configRevision: 0, validated: false }
      state.openrouterModels = []
      state.custom = { configured: false, apiFormat: 'CHAT_COMPLETIONS', baseUrl: '', endpointPreview: null, hasKey: false, maskedKey: null, selectedModel: '', configRevision: 0, validated: false }
      state.customDiscover = { models: [], manualModel: false, endpointPreview: null, failAuth: false }
      statusManual = false
      await page.goto('/settings')
      await expect(page.getByTestId('settings-page')).toBeVisible()
      await expect(page.getByTestId('active-provider-name')).toContainText('OpenCode Zen')
      await page.screenshot({ path: SHOTS + '/settings-opencode-active-' + suffix + '.png', fullPage: true })

      await page.getByTestId('provider-tab-openrouter').click()
      await expect(page.getByTestId('openrouter-card')).toBeVisible()
      await page.screenshot({ path: SHOTS + '/settings-openrouter-unconfigured-' + suffix + '.png', fullPage: true })

      state.openrouterModels = ['openrouter/free', 'meta-llama/llama-3:free', 'qwen/qwen3:free']
      await page.getByTestId('openrouter-api-key').fill('sk-or-v1-mock')
      await page.getByTestId('openrouter-probe').click()
      await expect(page.getByTestId('openrouter-model')).toBeEnabled()
      await page.screenshot({ path: SHOTS + '/settings-openrouter-models-' + suffix + '.png', fullPage: true })

      state.openrouter = { configured: true, maskedKey: '••••5678', selectedModel: 'qwen/qwen3:free', configRevision: 1, validated: true }
      statusManual = false
      await page.goto('/settings')
      await page.getByTestId('provider-tab-openrouter').click()
      await expect(page.getByTestId('openrouter-model')).toHaveValue('qwen/qwen3:free')
      await page.screenshot({ path: SHOTS + '/settings-openrouter-persisted-' + suffix + '.png', fullPage: true })

      const formats = [
        { v: 'CHAT_COMPLETIONS', shot: 'chat', base: 'http://localhost:11434/v1', preview: 'http://localhost:11434/v1/chat/completions' },
        { v: 'RESPONSES', shot: 'responses', base: 'https://gateway.example/v1', preview: 'https://gateway.example/v1/responses' },
      ] as const
      for (const f of formats) {
        state.customDiscover = { models: ['custom-model-a', 'custom-model-with-a-very-long-id-that-tests-ellipsis-behavior-in-dropdowns'], manualModel: false, endpointPreview: f.preview, failAuth: false }
        await page.getByTestId('provider-tab-custom').click()
        await expect(page.getByTestId('custom-card')).toBeVisible()
        await page.getByTestId('custom-format').selectOption(f.v)
        await page.getByTestId('custom-base-url').fill(f.base)
        await page.getByTestId('custom-discover').click()
        await expect(page.getByTestId('custom-model')).toBeVisible()
        await page.screenshot({ path: SHOTS + '/settings-custom-' + f.shot + '-' + suffix + '.png', fullPage: true })
      }

      state.customDiscover = { models: [], manualModel: true, endpointPreview: 'http://localhost:11434/v1/chat/completions', failAuth: false }
      await page.getByTestId('provider-tab-custom').click()
      await page.getByTestId('custom-base-url').fill('http://localhost:11434/v1')
      await page.getByTestId('custom-discover').click()
      await expect(page.getByTestId('custom-model-id')).toBeVisible()
      await page.screenshot({ path: SHOTS + '/settings-custom-manual-' + suffix + '.png', fullPage: true })

      state.custom = { configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'https://gateway.example/v1', endpointPreview: 'https://gateway.example/v1/chat/completions', hasKey: true, maskedKey: '••••9999', selectedModel: 'custom-model-a', configRevision: 3, validated: false }
      state.customDiscover = { models: [], manualModel: false, endpointPreview: null, failAuth: true }
      await page.getByTestId('custom-base-url').fill('https://gateway.example/v1')
      await page.getByTestId('custom-discover').click()
      await expect(page.getByTestId('custom-error')).toBeVisible()
      await page.screenshot({ path: SHOTS + '/settings-custom-error-' + suffix + '.png', fullPage: true })

      state.customDiscover.failAuth = false
      state.custom = { configured: true, apiFormat: 'RESPONSES', baseUrl: 'https://gateway.example/v1', endpointPreview: 'https://gateway.example/v1/responses', hasKey: false, maskedKey: null, selectedModel: 'custom-model-a', configRevision: 4, validated: true }
      await page.goto('/settings')
      await page.getByTestId('provider-tab-custom').click()
      await expect(page.getByTestId('custom-state')).toContainText('配置有效')
      await page.screenshot({ path: SHOTS + '/settings-custom-validated-inactive-' + suffix + '.png', fullPage: true })

      state.custom = { configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1', endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false, maskedKey: null, selectedModel: 'custom-model-a', configRevision: 2, validated: false }
      statusManual = false
      await page.goto('/settings')
      await page.getByTestId('provider-tab-custom').click()
      await expect(page.getByTestId('custom-model')).toHaveValue('custom-model-a')
      await page.screenshot({ path: SHOTS + '/settings-custom-persisted-' + suffix + '.png', fullPage: true })

      state.custom = { configured: true, apiFormat: 'CHAT_COMPLETIONS', baseUrl: 'http://localhost:11434/v1', endpointPreview: 'http://localhost:11434/v1/chat/completions', hasKey: false, maskedKey: null, selectedModel: 'typed-model', configRevision: 3, validated: true }
      statusManual = true
      await page.goto('/settings')
      await page.getByTestId('provider-tab-custom').click()
      await expect(page.getByTestId('custom-model-id')).toHaveValue('typed-model')
      await page.screenshot({ path: SHOTS + '/settings-custom-manual-persisted-' + suffix + '.png', fullPage: true })

      state.active = 'CUSTOM'
      await page.goto('/settings')
      await page.getByTestId('provider-tab-custom').click()
      await expect(page.getByTestId('custom-state')).toContainText('当前使用')
      await page.screenshot({ path: SHOTS + '/settings-custom-active-' + suffix + '.png', fullPage: true })
    }
  })
})
