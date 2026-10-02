import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { safeWebUrl, resolveWebCitations } from '../webSources'
import { sanitizeGaResourceRefs } from '../../state/globalAssistantStore'
import GaCitedAnswer from '../../components/GaCitedAnswer.vue'
const id = 'a1111111-1111-4111-8111-111111111111'
const raw = { kind: 'WEB_SOURCE', id, label: '官方文档', metadata: { url: 'https://docs.langchain.com/oss/python/langchain/agents', sourceRef: 'web:' + id, fetchedAt: '2026-10-02T00:00:00Z', contentStage: 'EXTRACTED_TEXT', authority: 'EXTERNAL_EVIDENCE', excerpt: '中文正文', truncated: false } }
describe('verified web sources', () => {
  it('restores bounded source metadata from persisted events', () => {
    const refs = sanitizeGaResourceRefs(JSON.parse(JSON.stringify([raw])))
    expect(refs).toHaveLength(1)
    expect(refs[0]?.metadata?.contentStage).toBe('EXTRACTED_TEXT')
    expect(resolveWebCitations('回答 [web:' + id + ']', refs)).toContain('https://docs.langchain.com/')
    expect(resolveWebCitations('回答 [web:' + id + ']', [])).toContain('未核验')
  })
  it('rejects private, credentialed, script and unverified sources', () => {
    for (const url of ['javascript:alert(1)', 'file:///tmp/a', 'http://127.0.0.1/x', 'http://10.0.0.1', 'https://user:secret@example.com', 'http://localhost']) {
      expect(safeWebUrl(url)).toBeNull()
      expect(sanitizeGaResourceRefs([{ ...raw, metadata: { ...raw.metadata, url } }])).toEqual([])
    }
    expect(sanitizeGaResourceRefs([{ ...raw, metadata: { ...raw.metadata, sourceRef: 'web:wrong' } }])).toEqual([])
  })
  it('renders safe citations and strips executable markup', () => {
    const wrapper = mount(GaCitedAnswer, { props: { content: '中文 [web:' + id + '] <script>alert(1)</script><img src=x onerror=alert(1)>', sources: sanitizeGaResourceRefs([raw]) } })
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('a').attributes('target')).toBe('_blank')
    expect(wrapper.find('a').attributes('rel')).toBe('noopener noreferrer')
  })
})
