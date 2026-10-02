import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SearchView from '../SearchSettingsView.vue'
import RetrievalView from '../RetrievalSettingsView.vue'
import { services, type RetrievalSettings, type SearchSettings } from '../api'

const search: SearchSettings = { enabled: true, configured: true, maskedKey: '••••1234', source: 'DATABASE', revision: 7, environmentAvailable: true, testCode: null, testedAt: null }
const retrieval: RetrievalSettings = {
  service: { config: { provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://fixture.invalid/v1', model: 'fixture-model', timeoutSeconds: 30, batchSize: 8, queryStrategy: 'raw-text.v1' }, configured: true, maskedKey: '••••1234', revision: 4, source: 'DATABASE', candidateProfile: null, dimensions: null, testCode: null, testedAt: null },
  index: { corpusId: 'help-corpus', name: '全局助手产品帮助', activeModel: 'old-model', activeDimensions: 1024, activeProfile: 'old-profile', activeGeneration: 'old-generation', readyEntries: 3, job: null },
}
const wrappers: ReturnType<typeof mount>[] = []
function button(wrapper: ReturnType<typeof mount>, text: string) { return wrapper.findAll('button').find(b => b.text() === text)! }
beforeEach(() => { vi.spyOn(services, 'search').mockResolvedValue(structuredClone(search)); vi.spyOn(services, 'retrieval').mockResolvedValue(structuredClone(retrieval)) })
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.restoreAllMocks() })

describe('service settings credential and index semantics', () => {
  it('omits retained credentials, keeps testing separate from saving, and confirms explicit clear', async () => {
    const save = vi.spyOn(services, 'saveSearch').mockResolvedValue(search)
    const test = vi.spyOn(services, 'testSearch').mockResolvedValue({ success: true, code: 'OK', target: 'SAVED' })
    const clear = vi.spyOn(services, 'clearSearch').mockResolvedValue({ ...search, configured: false, enabled: false, maskedKey: null })
    const w = mount(SearchView); wrappers.push(w); await flushPromises()
    expect(w.find('input[type="password"]').exists()).toBe(false)
    await button(w, '保存').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith({ enabled: true, revision: 7, importEnvironment: false })
    expect(test).not.toHaveBeenCalled()
    await button(w, '测试连接').trigger('click'); await flushPromises(); expect(save).toHaveBeenCalledTimes(1)
    await button(w, '清除配置').trigger('click'); expect(clear).not.toHaveBeenCalled()
    await button(w, '确认清除').trigger('click'); await flushPromises(); expect(clear).toHaveBeenCalledWith(7)
    expect(w.text()).toContain('启动环境中的旧密钥不会自动恢复')
  })
  it('a draft embedding probe is invalidated by field edits; saved candidates do not activate the index', async () => {
    vi.spyOn(services, 'testRetrieval').mockResolvedValue({ success: true, code: 'OK', target: 'DRAFT', dimensions: 3, testId: 'receipt-id' })
    const save = vi.spyOn(services, 'saveRetrieval').mockResolvedValue(retrieval)
    const activate = vi.spyOn(services, 'activate')
    const w = mount(RetrievalView); wrappers.push(w); await flushPromises()
    await w.get('#embedding-model').setValue('draft-model')
    await button(w, '测试连接').trigger('click'); await flushPromises()
    expect(w.text()).toContain('实际返回 3 维')
    await button(w, '保存候选配置').trigger('click'); await flushPromises()
    expect(save.mock.calls[0]?.[0]).toMatchObject({ testId: 'receipt-id', revision: 4 })
    expect(save.mock.calls[0]?.[0]).not.toHaveProperty('apiKey'); expect(activate).not.toHaveBeenCalled()
    await w.get('#embedding-model').setValue('draft-model')
    await button(w, '测试连接').trigger('click'); await flushPromises()
    await w.get('#embedding-model').setValue('another-model')
    await button(w, '保存候选配置').trigger('click'); await flushPromises()
    expect(save.mock.calls[1]?.[0]).not.toHaveProperty('testId')
  })
  it('shows failure with the old active model and only retries the chosen durable job', async () => {
    vi.mocked(services.retrieval).mockResolvedValue({ ...retrieval, index: { ...retrieval.index, job: { id: 'failed-job', profile_id: 'candidate-profile', state: 'FAILED', processed: 2, total: 3, error_code: 'EMBEDDING_TIMEOUT' } } })
    const retry = vi.spyOn(services, 'retry').mockResolvedValue(retrieval)
    const w = mount(RetrievalView); wrappers.push(w); await flushPromises()
    expect(w.text()).toContain('重建失败，仍使用原索引'); expect(w.text()).toContain('old-model')
    await button(w, '重试失败任务').trigger('click'); await flushPromises(); expect(retry).toHaveBeenCalledWith('failed-job')
  })
})
