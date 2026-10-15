import { test, expect } from './helpers'

for (const [status, text, provider, model] of [
  // 旧的聚合状态(旧 Python 后端):只做通用指引,不再假设 Ollama/qwen3-embedding:0.6b。
  ['OLLAMA_UNAVAILABLE', '向量服务', null, null],
  ['STORE_UNAVAILABLE', '共享令牌', null, null],
  ['HELP_INDEX_NOT_READY', '帮助索引正在准备', null, null],
  // 新的可用性语义:云端凭据缺失指引处理凭据,而不是安装 Ollama;带非敏感提供商/模型信息。
  ['EMBEDDING_CREDENTIAL_UNAVAILABLE', '凭据缺失', 'OPENAI_COMPATIBLE', 'text-embedding-v4'],
  ['EMBEDDING_CONNECTION_FAILED', '无法连接向量服务（OPENAI_COMPATIBLE · text-embedding-v4）', 'OPENAI_COMPATIBLE', 'text-embedding-v4'],
  ['EMBEDDING_MODEL_NOT_FOUND', '未找到模型（OLLAMA · nomic-embed-text）', 'OLLAMA', 'nomic-embed-text'],
  ['EMBEDDING_NOT_CONFIGURED', '尚未配置向量服务', null, null],
] as const) {
  test('controlled readiness notice: ' + status, async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('spec-agent:global-assistant:panel:v1', 'open'))
    await page.route('**/api/v1/global-assistant/tools', route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({
        engineVersion: 'langchain-ga.v1', webConfigured: false, retrievalReady: false, retrievalStatus: status,
        retrievalProvider: provider, retrievalModel: model, capabilities: [],
      }),
    }))
    await page.route('**/api/v1/global-assistant/threads**', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(route.request().method() === 'POST' ? { threadId: '11111111-1111-4111-8111-111111111111' } : []) }))
    await page.goto('/projects')
    await expect(page.getByTestId('ga-web-unconfigured')).toContainText('联网未配置')
    await expect(page.getByTestId('ga-retrieval-unready')).toContainText(text)
    if (['OLLAMA_UNAVAILABLE', 'EMBEDDING_NOT_CONFIGURED', 'EMBEDDING_CREDENTIAL_UNAVAILABLE', 'EMBEDDING_CONNECTION_FAILED', 'EMBEDDING_MODEL_NOT_FOUND'].includes(status)) {
      await expect(page.getByTestId('ga-retrieval-settings-link')).toContainText('知识检索设置')
    } else {
      await expect(page.getByTestId('ga-retrieval-settings-link')).toHaveCount(0)
    }
  })
}
