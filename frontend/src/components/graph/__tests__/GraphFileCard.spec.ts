import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import GraphKnowledgeNode from '@/components/graph/GraphKnowledgeNode.vue'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'
import type { GraphWorkspaceNodeView } from '@/api/types'

const HandleStub = { name: 'Handle', render: () => null }

/** 预览弹窗是 teleport 到 body 的最上层对话框，只能从那里查。 */
function previewDialog(): HTMLElement | null {
  return document.body.querySelector('[data-test="file-preview-dialog"]')
}

function previewText(testId: string): string | null {
  return document.body.querySelector(`[data-test="${testId}"]`)?.textContent ?? null
}

function mountNode(content: Record<string, unknown>) {
  const node: GraphWorkspaceNodeView = {
    id: 'res-1',
    projectId: 'p1',
    parentNodeId: null,
    supersedesNodeId: null,
    question: '',
    purpose: null,
    options: [],
    allowFreeAnswer: false,
    allowMultiSelect: false,
    createdAt: '2026-09-16T08:30:00Z',
    kind: 'RESOURCE',
    subtype: 'FILE',
    content,
    authorKind: 'USER',
    knowledgeStatus: null,
    userEditableDraft: false,
  }
  const data: SpecAgentGraphNodeData = {
    node,
    projectId: 'p1',
    routeIds: [],
    visibleRouteIds: [],
    answers: [],
    routeStates: [],
    primaryAnswer: null,
    answerPresentationMode: 'single-route',
    readingRouteId: null,
    isCurrent: false,
    canAnswer: false,
    isExpanded: false,
    isShared: false,
    isLatest: false,
    qLabel: null,
    visualWeight: 'normal',
  }
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(GraphKnowledgeNode, {
    props: { data },
    global: { stubs: { Handle: HandleStub }, plugins: [pinia] },
  })
}

describe('file resource card', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // 弹窗挂在 body 上，用例之间必须清干净，否则会互相看到对方的弹窗。
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('shows file icon, file name and upload time instead of the full content', () => {
    const wrapper = mountNode({
      fileName: '需求说明.pdf',
      text: '应该被隐藏的很长正文',
      fileDataUrl: 'data:application/pdf;base64,AAAA',
    })

    expect(wrapper.find('[data-test="resource-file-card"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="resource-file-name"]').text()).toBe('需求说明.pdf')
    expect(wrapper.get('[data-test="resource-file-uploaded-at"]').text()).toContain('上传于')
    // 正文不上卡片，只在预览弹窗中显示。
    expect(wrapper.find('[data-test="knowledge-text"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('应该被隐藏的很长正文')
  })

  it('opens the large preview dialog in the top layer with the original file on click', async () => {
    const wrapper = mountNode({
      fileName: '截图.png',
      text: 'OCR 文本',
      fileDataUrl: 'data:image/png;base64,AAAA',
    })

    expect(previewDialog()).toBeNull()
    await wrapper.get('[data-test="resource-file-card"]').trigger('click')

    expect(previewDialog()).not.toBeNull()
    // 关键回归：弹窗绝不能渲染在节点内部 —— 节点带 CSS transform，
    // 就地渲染会让 position:fixed 相对节点定位、并把 z-index 困在节点的
    // 层叠上下文里，弹窗就会变成节点里的一小块。
    expect(wrapper.find('[data-test="file-preview-dialog"]').exists()).toBe(false)
    expect(previewText('file-preview-name')).toBe('截图.png')
    expect(previewText('file-preview-original')).not.toBeNull()

    ;(document.body.querySelector('[data-test="file-preview-close"]') as HTMLElement).click()
    await nextTick()
    expect(previewDialog()).toBeNull()
  })

  it('still lets the user preview extracted text when no original was saved', async () => {
    const wrapper = mountNode({ fileName: '旧文档.txt', text: '仅存正文' })

    await wrapper.get('[data-test="resource-file-card"]').trigger('click')
    expect(previewText('file-preview-missing')).toContain('仅保留提取的文本内容')
    expect(previewText('file-preview-extracted')).toContain('仅存正文')
  })

  it('explains why a Word document cannot be rendered instead of claiming a legacy file', async () => {
    const wrapper = mountNode({ fileName: '需求说明.docx', text: '解析出的正文' })

    await wrapper.get('[data-test="resource-file-card"]').trigger('click')
    // .docx 即使有原件也无法在浏览器里渲染，提示语必须说明这一点，
    // 不能沿用「原件保存功能上线前」那种说法（那是另一回事）。
    expect(previewText('file-preview-missing')).toContain('无法在浏览器内直接渲染')
    expect(previewText('file-preview-extracted')).toContain('解析出的正文')
  })

  it('renders Markdown as a document rather than showing its source', async () => {
    const wrapper = mountNode({
      fileName: 'README-使用说明.md',
      text: '# 本地部署\n\n- 第一步\n- 第二步\n',
      fileDataUrl: 'data:text/markdown;base64,IyDmnKzlnLDpg6jnvbI=',
    })

    await wrapper.get('[data-test="resource-file-card"]').trigger('click')
    expect(previewText('file-preview-kind')).toBe('Markdown')
    // 复用全局助手的富文本渲染：列表必须成为真正的 <li>，而不是字面量 “- 第一步”。
    const rendered = document.body.querySelector('[data-test="file-preview-original"]')
    expect(rendered?.querySelectorAll('li')).toHaveLength(2)
    expect(rendered?.textContent).not.toContain('- 第一步')
  })

  it('labels structured data with its format and keeps it monospaced', async () => {
    const wrapper = mountNode({
      fileName: 'package.json',
      text: '{"name":"spec-agent"}',
      fileDataUrl: 'data:application/json;base64,e30=',
    })

    await wrapper.get('[data-test="resource-file-card"]').trigger('click')
    expect(previewText('file-preview-kind')).toBe('JSON')
    // 正文渲染统一交给 ResourceBody：等宽样式落在它内部的 <pre> 上。
    const body = document.body.querySelector('[data-test="file-preview-original"]')
    expect(body?.querySelector('.resource-body__text--code')).not.toBeNull()
  })
})

/** 工具条在 body 上的弹窗里，点击必须走原生 DOM。 */
function clickTool(testId: string): void {
  ;(document.body.querySelector(`[data-test="${testId}"]`) as HTMLElement).click()
}
function bodyEl(): HTMLElement {
  return document.body.querySelector('.file-preview__body') as HTMLElement
}
function toolDisabled(testId: string): boolean {
  return (document.body.querySelector(`[data-test="${testId}"]`) as HTMLButtonElement).disabled
}

describe('file preview reader zoom', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  async function openMarkdown() {
    const wrapper = mountNode({
      fileName: 'README.md',
      text: '# 标题\n\n正文\n',
      fileDataUrl: 'data:text/markdown;base64,QQ==',
    })
    await wrapper.get('[data-test="resource-file-card"]').trigger('click')
    return wrapper
  }

  it('steps the zoom level and scales flow text with it', async () => {
    await openMarkdown()
    expect(previewText('file-preview-zoom-level')).toBe('100%')

    clickTool('file-preview-zoom-in')
    await nextTick()
    expect(previewText('file-preview-zoom-level')).toBe('125%')
    // 流式内容用 CSS zoom 放大：字号与排版一起等比变化，文本仍自然回流。
    expect(document.body.querySelector('.file-preview__flow')?.getAttribute('style')).toContain('1.25')

    clickTool('file-preview-zoom-out')
    await nextTick()
    expect(previewText('file-preview-zoom-level')).toBe('100%')

    clickTool('file-preview-zoom-out')
    await nextTick()
    expect(previewText('file-preview-zoom-level')).toBe('80%')
  })

  it('clamps at the ends of the zoom range and resets from the level button', async () => {
    await openMarkdown()

    // 100% 时还能再缩小，但连续缩到下限后按钮必须禁用，不能无限缩。
    for (let i = 0; i < 10; i += 1) {
      clickTool('file-preview-zoom-out')
      await nextTick()
    }
    expect(previewText('file-preview-zoom-level')).toBe('50%')
    expect(toolDisabled('file-preview-zoom-out')).toBe(true)

    clickTool('file-preview-zoom-reset')
    await nextTick()
    expect(previewText('file-preview-zoom-level')).toBe('100%')

    for (let i = 0; i < 20; i += 1) {
      clickTool('file-preview-zoom-in')
      await nextTick()
    }
    expect(previewText('file-preview-zoom-level')).toBe('300%')
    expect(toolDisabled('file-preview-zoom-in')).toBe(true)
  })

  it('sizes canvas content without CSS zoom, and allows panning once magnified', async () => {
    const wrapper = mountNode({
      fileName: '截图.png',
      text: 'OCR',
      fileDataUrl: 'data:image/png;base64,AAAA',
    })
    await wrapper.get('[data-test="resource-file-card"]').trigger('click')

    // 图片/PDF 不能用 CSS zoom：zoom 会连 max-height:100% 一起放大，
    // 结果永远是「刚好塞进窗口」，放大等于没发生。
    const canvas = document.body.querySelector('.file-preview__canvas') as HTMLElement
    expect(canvas.getAttribute('style') ?? '').not.toContain('zoom')
    const image = document.body.querySelector('.file-preview__image') as HTMLElement
    // 量到自然尺寸之前先用窗口约束兜底（jsdom 里图片不会加载，就停在这一态）。
    expect(image.classList.contains('file-preview__image--measured')).toBe(false)

    clickTool('file-preview-zoom-in')
    await nextTick()
    // 放大后可拖拽平移，才能把超出视口的边角拽回来。
    expect(bodyEl().classList.contains('file-preview__body--grabbable')).toBe(true)

    clickTool('file-preview-zoom-reset')
    await nextTick()
    expect(bodyEl().classList.contains('file-preview__body--grabbable')).toBe(false)
  })

  it('resets zoom to fit when another file is opened', async () => {
    const wrapper = await openMarkdown()
    clickTool('file-preview-zoom-in')
    await nextTick()
    expect(previewText('file-preview-zoom-level')).toBe('125%')

    clickTool('file-preview-close')
    await nextTick()
    await wrapper.get('[data-test="resource-file-card"]').trigger('click')
    expect(previewText('file-preview-zoom-level')).toBe('100%')
  })

  it('offers word wrap for code only, and starts unwrapped', async () => {
    const wrapper = mountNode({
      fileName: 'app.ts',
      text: 'const a = 1\n',
      fileDataUrl: 'data:text/plain;base64,QQ==',
    })
    await wrapper.get('[data-test="resource-file-card"]').trigger('click')

    const body = () => document.body.querySelector('[data-test="file-preview-original"]') as HTMLElement
    const codePre = () => body().querySelector('.resource-body__text') as HTMLElement
    // 代码默认不折行：缩进结构优先，长行横向滚动。
    expect(codePre().classList.contains('resource-body__text--wrapped')).toBe(false)
    expect(toolDisabled('file-preview-wrap')).toBe(false)

    clickTool('file-preview-wrap')
    await nextTick()
    expect(codePre().classList.contains('resource-body__text--wrapped')).toBe(true)
    expect(previewText('file-preview-wrap')).toBe('已折行')
  })

  it('leaves the wrap control inert for content that is not code', async () => {
    await openMarkdown()
    expect(toolDisabled('file-preview-wrap')).toBe(true)
  })
})
