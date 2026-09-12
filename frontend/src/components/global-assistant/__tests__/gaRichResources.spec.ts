import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import RichAssistantText from '../RichAssistantText.vue'
import ProjectResourceList from '../ProjectResourceList.vue'
import { sanitizeGaResourceRefs } from '@/stores/globalAssistantStore'

const NL = String.fromCharCode(10)
describe('RichAssistantText', () => {
  it('renders headings bold lists code links', () => {
    const md = ['## 标题','','**粗体** 和 *斜体*','','- a','- b','','1. x','2. y','','`code`'].join(NL)
    const w = mount(RichAssistantText, { props: { content: md + NL + NL + '[link](https://example.com)' } })
    const html = w.html()
    expect(html).toContain('<h2')
    expect(html).toContain('<strong')
    expect(html).toContain('<ul')
    expect(html).toContain('<ol')
    expect(html).toContain('<code')
  })
  it('downgrades h1', () => {
    const w = mount(RichAssistantText, { props: { content: '# 大标题' + NL + NL + '正文' } })
    expect(w.html()).not.toContain('<h1')
    expect(w.html()).toContain('<h2')
  })
  it('strips unsafe', () => {
    const w = mount(RichAssistantText, { props: { content: 'hello' } })
    expect(w.find('[data-test=ga-rich-text]').exists()).toBe(true)
    const w2 = mount(RichAssistantText, { props: { content: '<b>hi</b>' } })
    expect(w2.html()).not.toContain('<script')
  })
})
describe('sanitizeGaResourceRefs', () => {
  it('keeps only valid PROJECT refs', () => {
    const good = '123e4567-e89b-12d3-a456-426614174000'
    const raw = [{ kind: 'PROJECT', id: good, label: 'A' }]
    expect(sanitizeGaResourceRefs(raw)).toHaveLength(1)
  })
  it('truncates labels by code points without splitting emoji', () => {
    const good = '123e4567-e89b-12d3-a456-426614174000'
    const title = 'x'.repeat(199) + '\uD83D\uDE00' + 'tail'
    const [ref] = sanitizeGaResourceRefs([{ kind: 'PROJECT', id: good, label: title }])
    expect(Array.from(ref.label).length).toBe(200)
    expect(ref.label.startsWith('x'.repeat(199) + '\uD83D\uDE00')).toBe(true)
  })
  it('keeps pure emoji long titles safe', () => {
    const good = '123e4567-e89b-12d3-a456-426614174000'
    const [ref] = sanitizeGaResourceRefs([{ kind: 'PROJECT', id: good, label: '\uD83D\uDE00'.repeat(250) }])
    expect(Array.from(ref.label).length).toBe(200)
  })
})
describe('ProjectResourceList', () => {
  it('clickable uuid navigation', async () => {
    const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: { template: '<div />' } }, { path: '/projects/:id', component: { template: '<div />' } }] })
    await router.push('/')
    await router.isReady()
    const id = '123e4567-e89b-12d3-a456-426614174000'
    const w = mount(ProjectResourceList, { props: { resources: [{ kind: 'PROJECT', id, label: '演示' }] }, global: { plugins: [router] } })
    const btn = w.find('[data-test=ga-resource-' + id + ']')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(router.currentRoute.value.path).toBe('/projects/' + id)
  })
})
