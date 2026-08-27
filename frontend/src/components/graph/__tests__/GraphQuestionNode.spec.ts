import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GraphQuestionNode from '@/components/graph/GraphQuestionNode.vue'
import type { SpecAgentGraphNodeData, GraphAnswerPresentation } from '@/graph/graphProjection'
import type { GraphWorkspaceNodeView, GraphWorkspaceOptionView } from '@/api/types'

/**
 * Vue Flow Handle stub: jsdom cannot run the real useVueFlow/useNode
 * context outside a VueFlow instance, so the node spec renders the anchor
 * handles as plain divs that expose every prop as an attribute. The real
 * component contract (8 invisible handles, source/target type, side
 * position, non-connectable flags) is asserted through these attributes.
 */
const HandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: true,
  render() {
    return h('div', { class: ['vue-flow__handle', 'graph-question-node__handle'] })
  },
})

/** Mounts the node with the Handle stub so jsdom-safe assertions can run. */
function mountNode(data: SpecAgentGraphNodeData, extra: Record<string, unknown> = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(GraphQuestionNode, {
    props: { data, submitting: false, pending: false, ...extra },
    global: { stubs: { Handle: HandleStub }, plugins: [pinia] },
  })
}

function option(id: string, label: string, impact: string | null): GraphWorkspaceOptionView {
  return { id, label, impact }
}

function nodeData(overrides: Partial<GraphWorkspaceNodeView> = {}): GraphWorkspaceNodeView {
  return {
    id: 'n1',
    projectId: 'p1',
    parentNodeId: null,
    supersedesNodeId: null,
    question: 'What outcome matters most?',
    purpose: 'Clarify the primary goal.',
    options: [
      option('opt-a', 'Product team', 'Fastest value'),
      option('opt-b', 'Engineering team', null),
    ],
    allowFreeAnswer: true,
    createdAt: '2026-08-18T00:00:00Z',
    kind: 'INTERACTION',
    subtype: 'QUESTION',
    content: {},
    authorKind: 'AGENT',
    knowledgeStatus: null,
    userEditableDraft: false,
    ...overrides,
  }
}

function answer(routeId: string, overrides: Partial<GraphAnswerPresentation> = {}): GraphAnswerPresentation {
  return {
    routeId,
    selectedOptionId: null,
    selectedOptionLabel: null,
    freeText: 'answer text',
    isPrimary: false,
    ...overrides,
  }
}

function currentData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: nodeData(),
    projectId: 'p1',
    routeIds: ['r1'],
    visibleRouteIds: ['r1'],
    answers: [],
    routeStates: [],
    primaryAnswer: null,
    answerPresentationMode: 'single-route',
    readingRouteId: 'r1',
    isCurrent: true,
    canAnswer: true,
    isExpanded: false,
    isShared: false,
    isLatest: false,
    qLabel: null,
    visualWeight: 'active',
    ...overrides,
  }
}

function historicalData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
  return {
    node: nodeData({ parentNodeId: 'n0' }),
    projectId: 'p1',
    routeIds: ['r1', 'r2'],
    visibleRouteIds: ['r1', 'r2'],
    answers: [
      answer('r1', { selectedOptionId: 'opt-a', selectedOptionLabel: 'Product team', freeText: 'Keep this exact user answer.', isPrimary: true }),
      answer('r2', { freeText: 'Second route answer.' }),
    ],
    routeStates: [
      {
        routeId: 'r1',
        answer: {
          routeId: 'r1',
          selectedOptionId: 'opt-a',
          selectedOptionLabel: 'Product team',
          freeText: 'Keep this exact user answer.',
          isPrimary: true,
        },
      },
      {
        routeId: 'r2',
        answer: {
          routeId: 'r2',
          selectedOptionId: null,
          selectedOptionLabel: null,
          freeText: 'Second route answer.',
          isPrimary: false,
        },
      },
    ],
    primaryAnswer: {
      routeId: 'r1',
      selectedOptionId: 'opt-a',
      selectedOptionLabel: 'Product team',
      freeText: 'Keep this exact user answer.',
      isPrimary: true,
    },
    answerPresentationMode: 'focused',
    readingRouteId: 'r1',
    isCurrent: false,
    canAnswer: false,
    isExpanded: false,
    isShared: true,
    isLatest: false,
    qLabel: null,
    visualWeight: 'normal',
    ...overrides,
  }
}

describe('graph question node', () => {
  it('renders current question, purpose, option labels and impacts verbatim', () => {
    const wrapper = mountNode(currentData())
    expect(wrapper.text()).toContain('What outcome matters most?')
    expect(wrapper.text()).toContain('Clarify the primary goal.')
    expect(wrapper.text()).toContain('Product team')
    expect(wrapper.text()).toContain('Fastest value')
    expect(wrapper.text()).toContain('Engineering team')
  })

  it('submits the exact backend option id with an option-only payload', async () => {
    const wrapper = mountNode(currentData())
    await wrapper.find('input[type=radio][value="opt-b"]').setValue()
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('submit-answer')?.[0]).toEqual([{ selectedOptionId: 'opt-b', freeText: null }])
  })

  it('submits a free-text-only payload', async () => {
    const wrapper = mountNode(currentData())
    await wrapper.find('[data-test="free-text"]').setValue('free text answer')
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('submit-answer')?.[0]).toEqual([{ selectedOptionId: null, freeText: 'free text answer' }])
  })

  it('submits combined option + free text payload', async () => {
    const wrapper = mountNode(currentData())
    await wrapper.find('input[type=radio][value="opt-a"]').setValue()
    await wrapper.find('[data-test="free-text"]').setValue('with explanation')
    await wrapper.find('[data-test="submit-answer"]').trigger('click')
    expect(wrapper.emitted('submit-answer')?.[0]).toEqual([
      { selectedOptionId: 'opt-a', freeText: 'with explanation' },
    ])
  })

  it('disables submit with no input and while submitting', async () => {
    const wrapper = mountNode(currentData())
    expect((wrapper.find('[data-test="submit-answer"]').attributes('disabled')) !== undefined).toBe(true)
    await wrapper.find('input[type=radio][value="opt-a"]').setValue()
    expect(wrapper.find('[data-test="submit-answer"]').attributes('disabled')).toBeUndefined()
    await wrapper.setProps({ submitting: true })
    expect(wrapper.find('[data-test="submit-answer"]').attributes('disabled')).toBeDefined()
  })

  it('free text is hidden when the node does not allow it', () => {
    const wrapper = mountNode(currentData({ node: nodeData({ allowFreeAnswer: false }) }))
    expect(wrapper.find('[data-test="free-text"]').exists()).toBe(false)
  })

  it('local answer input resets when the node changes', async () => {
    const wrapper = mountNode(currentData())
    await wrapper.find('[data-test="free-text"]').setValue('stale draft')
    await wrapper.setProps({ data: currentData({ node: nodeData({ id: 'n2' }) }) })
    expect((wrapper.find('[data-test="free-text"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('historical node has no answer inputs and shows the full answer summary', () => {
    const wrapper = mountNode(historicalData())
    expect(wrapper.find('[data-test="free-text"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="submit-answer"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Product team')
    expect(wrapper.text()).toContain('Keep this exact user answer.')
    expect(wrapper.find('[data-test="answer-summary"]').exists()).toBe(true)
  })

  it('historical freeText carries the bounded-summary class so a long answer never overflows the graph card', () => {
    const wrapper = mountNode(historicalData({
      answers: [
        { routeId: 'r1', selectedOptionId: null, selectedOptionLabel: null,
          freeText: 'A'.repeat(5000), isPrimary: true },
      ],
    }))
    const clamped = wrapper.find('[data-test="clamped-free-text"]')
    expect(clamped.exists()).toBe(true)
    expect(clamped.classes()).toContain('graph-answer-text--clamped')
    // current answerable node must NOT carry the clamp class.
    const current = mountNode(currentData())
    expect(current.find('[data-test="clamped-free-text"]').exists()).toBe(false)
  })

  it('action rail renders outside the node body as a right-side vertical stack', () => {
    const wrapper = mountNode(historicalData())
    const rail = wrapper.find('.graph-node-actions--toolbar')
    expect(rail.exists()).toBe(true)
    // 轨道是 article 的直接子元素，绝不在卡片主体内占位。
    expect(rail.element.parentElement).toBe(wrapper.element)
    expect(wrapper.find('[data-test="node-body"] .graph-node-actions--toolbar').exists()).toBe(false)
    for (const id of ['fork-node', 'reanswer-node', 'regenerate-node', 'contextual-ai']) {
      expect(rail.find(`[data-test="${id}"]`).exists()).toBe(true)
    }
  })

  it('action rail stays visible on selected node (selection adds the --selected class)', async () => {
    const wrapper = mountNode(historicalData(), { selected: true })
    const article = wrapper.find('[data-test="graph-question-node"]')
    expect(article.classes()).toContain('graph-question-node--selected')
  })

  it('action rail is keyboard focusable: tabindex is 0 and role=toolbar', () => {
    const wrapper = mountNode(historicalData())
    const rail = wrapper.find('.graph-node-actions--toolbar')
    expect(rail.attributes('tabindex')).toBe('0')
    expect(rail.attributes('role')).toBe('toolbar')
    expect(rail.attributes('aria-label')).toBe('节点操作')
  })

  it('current answerable node renders no action rail (answering stays in the card)', () => {
    const wrapper = mountNode(currentData())
    expect(wrapper.find('.graph-node-actions--toolbar').exists()).toBe(false)
  })

  it('historical node never expands verbose route history inside the graph card', async () => {
    const expanded = historicalData({ isExpanded: true })
    const wrapper = mountNode(expanded)
    expect(wrapper.find('[data-test="answer-summary"]').exists()).toBe(true)
    expect(wrapper.find('.graph-node-details').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Second route answer.')
  })

  it('current node gets the large class and historical node the compact class', () => {
    const current = mountNode(currentData())
    expect(current.find('.graph-question-node--current').exists()).toBe(true)
    const historical = mountNode(historicalData())
    expect(historical.find('.graph-question-node--historical').exists()).toBe(true)
  })

  it('header is exposed as the drag handle and body controls are not draggable', () => {
    const wrapper = mountNode(currentData())
    expect(wrapper.find('[data-test="node-drag-handle"]').classes()).toContain('graph-question-node__header')
    const body = wrapper.find('.graph-question-node__body')
    expect(body.classes()).toContain('nodrag')
    expect(wrapper.find('[data-test="free-text"]').classes()).toContain('nodrag')
    expect(wrapper.find('button[data-test="submit-answer"]').classes()).toContain('nodrag')
  })

  it('historical fork/regenerate only emit intent upward', async () => {
    const wrapper = mountNode(historicalData())
    await wrapper.find('[data-test="fork-node"]').trigger('click')
    await wrapper.find('[data-test="regenerate-node"]').trigger('click')
    expect(wrapper.emitted('fork')?.[0]).toEqual(['n1'])
    expect(wrapper.emitted('regenerate')?.[0]).toEqual(['n1'])
  })

  it('shared nodes expose the real current-reading selector without duplicating route history', async () => {
    const wrapper = mountNode(
      historicalData({
        routeMembership: [
          { routeId: 'r1', label: 'Initial', lifecycleStatus: 'open', isActive: true },
          { routeId: 'r2', label: 'Route-B', lifecycleStatus: 'archived', isActive: false },
        ],
      }),
      { selected: true },
    )
    const selector = wrapper.find('[data-test="reading-route-select"]')
    expect(selector.exists()).toBe(true)
    expect((selector.element as HTMLSelectElement).value).toBe('r1')
    await selector.setValue('r2')
    expect(wrapper.emitted('focus-route')?.[0]).toEqual(['r2'])
    expect(wrapper.text()).not.toContain('Second route answer.')
    expect(wrapper.emitted('fork')).toBeUndefined()
    expect(wrapper.emitted('submit-answer')).toBeUndefined()
    expect(wrapper.text()).toContain('Initial')
    expect(wrapper.text()).toContain('Route-B')
    expect(wrapper.text()).not.toContain('共享 · 2 条路线')
  })

  it('renders runtime status separately on a pending projection and exposes retry', async () => {
    const wrapper = mountNode(historicalData({
      node: nodeData({ id: 'pending:run-1', question: '下一步问题生成失败' }),
      routeIds: ['r2'],
      visibleRouteIds: ['r2'],
      routeMembership: [{ routeId: 'r2', label: '分支路线', lifecycleStatus: 'open', isActive: true }],
      isShared: false,
      runtimeStatus: 'FAILED',
      runtimePhase: 'FAILED',
      runtimeMessage: '模型服务不可用',
    }))
    expect(wrapper.find('[data-test="pending-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="runtime-status"]').text()).toContain('运行失败')
    expect(wrapper.text()).toContain('模型服务不可用')
    expect(wrapper.find('[data-test="contextual-ai"]').exists()).toBe(false)
    await wrapper.find('[data-test="retry-pending"]').trigger('click')
    expect(wrapper.emitted('retry-pending')).toHaveLength(1)
  })

  it('renders a shared node as neutral when Focus is absent', () => {
    const wrapper = mountNode(historicalData({
      readingRouteId: null,
      primaryAnswer: null,
      routeMembership: [
        { routeId: 'r1', label: '主路线', lifecycleStatus: 'open', isActive: true },
        { routeId: 'r2', label: '分支路线 1', lifecycleStatus: 'open', isActive: false },
      ],
    }))
    expect((wrapper.find('[data-test="reading-route-select"]').element as HTMLSelectElement).value).toBe('')
    expect(wrapper.find('[data-test="reading-route-select"]').text()).toContain('未选择')
  })
})
describe('shared node route-specific waiting state', () => {
  function waitingData(overrides: Partial<SpecAgentGraphNodeData> = {}): SpecAgentGraphNodeData {
    return {
      node: nodeData({ parentNodeId: 'n0' }),
      projectId: 'p1',
      routeIds: ['r1', 'r2'],
      visibleRouteIds: ['r1', 'r2'],
      answers: [
        answer('r1', { freeText: 'A answer on shared node.', isPrimary: false }),
      ],
      routeStates: [
        {
          routeId: 'r1',
          answer: {
            routeId: 'r1',
            selectedOptionId: null,
            selectedOptionLabel: null,
            freeText: 'A answer on shared node.',
            isPrimary: false,
          },
        },
        { routeId: 'r2', answer: null },
      ],
      primaryAnswer: null,
      answerPresentationMode: 'focused',
      readingRouteId: 'r2',
      isCurrent: false,
      canAnswer: false,
      isExpanded: false,
      isShared: true,
      isLatest: false,
      qLabel: null,
      visualWeight: 'focus',
      ...overrides,
    }
  }

  it('Focus=B without an answer shows B waiting + the question itself, and offers a resume affordance when B is an explicit reading route', async () => {
    const wrapper = mountNode(waitingData())
    // 摘要显式等待并直接显示问题；A 的回答不作为 primary 展示。
    const waiting = wrapper.find('[data-test="waiting-summary"]')
    expect(waiting.exists()).toBe(true)
    expect(waiting.text()).toContain('当前查看路线 · 等待回答')
    expect(waiting.find('[data-test="waiting-question"]').text()).toBe('What outcome matters most?')
    // readingRouteId='r2'，显式唤醒入口可见。
    const button = waiting.find('[data-test="answer-this-question"]')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('回答这个问题')
    await button.trigger('click')
    expect(wrapper.emitted('activate-route')?.[0]).toEqual(['r2'])
  })

  it('Focus=B without an answer: when readingRouteId is null, no resume affordance is exposed (avoids source route ambiguity)', () => {
    const wrapper = mountNode(waitingData({
      readingRouteId: null,
      answerPresentationMode: 'focused',
    }))
    // readingRouteId = null 时仍走 focused 模式但无明确 source route，唤醒按钮
    // 必须隐藏（避免拿 Active/first/latest 冒充 source route）。
    expect(wrapper.find('[data-test="answer-this-question"]').exists()).toBe(false)
  })
})

describe('node body click handling', () => {
  it('non-interactive body surface does not stop click propagation (node stays selectable)', async () => {
    const wrapper = mountNode(historicalData())
    const article = wrapper.element
    let bubbled = 0
    article.addEventListener('click', () => { bubbled += 1 })
    // 点击 body 的非交互区域（历史节点摘要文本所在区域）。
    const body = wrapper.find('[data-test="node-body"]').element as HTMLElement
    body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(bubbled).toBeGreaterThanOrEqual(1)
  })

  it('interactive controls stop propagation so they never break selection', async () => {
    const wrapper = mountNode(currentData())
    const article = wrapper.element
    let bubbled = 0
    article.addEventListener('click', () => { bubbled += 1 })
    const textarea = wrapper.find('[data-test="free-text"]').element as HTMLElement
    textarea.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    const submit = wrapper.find('[data-test="submit-answer"]').element as HTMLElement
    submit.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    const toggle = wrapper.find('[data-test="toggle-expanded"]')
    expect(bubbled).toBe(0)
    void toggle
  })

  it('historical action buttons stop propagation too', async () => {
    const wrapper = mountNode(historicalData())
    const article = wrapper.element
    let bubbled = 0
    article.addEventListener('click', () => { bubbled += 1 })
    const forkBtn = wrapper.find('[data-test="fork-node"]').element as HTMLElement
    forkBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(bubbled).toBe(0)
  })
})

describe('graph node edge anchors (adaptive four-side handles)', () => {
  const handleIds = [
    'source-left',
    'source-right',
    'source-top',
    'source-bottom',
    'target-left',
    'target-right',
    'target-top',
    'target-bottom',
  ] as const

  it('exposes all eight source/target anchor handles', () => {
    const wrapper = mountNode(currentData())
    for (const id of handleIds) {
      const handle = wrapper.find(`[id="${id}"]`)
      expect(handle.exists(), `missing handle ${id}`).toBe(true)
      expect(handle.classes()).toContain('vue-flow__handle')
      expect(handle.classes()).toContain('graph-question-node__handle')
    }
  })

  it('gives each handle the right source/target type and side position', () => {
    const wrapper = mountNode(historicalData())
    const cases: [string, string, string][] = [
      ['source-left', 'source', 'left'],
      ['source-right', 'source', 'right'],
      ['source-top', 'source', 'top'],
      ['source-bottom', 'source', 'bottom'],
      ['target-left', 'target', 'left'],
      ['target-right', 'target', 'right'],
      ['target-top', 'target', 'top'],
      ['target-bottom', 'target', 'bottom'],
    ]
    for (const [id, type, position] of cases) {
      const handle = wrapper.find(`[id="${id}"]`)
      expect(handle.attributes('type'), id).toBe(type)
      expect(handle.attributes('position'), id).toBe(position)
    }
  })

  it('exposes draggable connection anchors: source starts and completes edges', () => {
    const wrapper = mountNode(currentData())
    for (const id of handleIds) {
      const handle = wrapper.find(`[id="${id}"]`)
      expect(handle.attributes('connectable'), id).toBe('true')
    }
    // source 锚点既可发起也可完成连接（target 锚点不参与指针命中，见样式）。
    for (const id of ['source-left', 'source-right', 'source-top', 'source-bottom']) {
      const handle = wrapper.find(`[id="${id}"]`)
      expect(handle.attributes('connectable-start'), id).toBe('true')
      expect(handle.attributes('connectable-end'), id).toBe('true')
      expect(handle.classes()).toContain('graph-question-node__handle--source')
    }
    for (const id of ['target-left', 'target-right', 'target-top', 'target-bottom']) {
      const handle = wrapper.find(`[id="${id}"]`)
      expect(handle.attributes('connectable-start'), id).toBe('false')
      expect(handle.attributes('connectable-end'), id).toBe('true')
      expect(handle.classes()).toContain('graph-question-node__handle--target')
    }
  })

  it('style.css keeps anchors hidden by default and reveals them on node hover for manual connection', () => {
    // Vitest runs with the frontend directory as cwd.
    const css = readFileSync(resolve(process.cwd(), 'src/style.css'), 'utf8')
    expect(css).toMatch(/\.graph-question-node__handle\s*{[^}]*pointer-events:\s*none/)
    expect(css).toMatch(/\.graph-question-node__handle\s*{[^}]*opacity:\s*0/)
    expect(css).toMatch(/\.graph-question-node:hover \.graph-question-node__handle--source\s*{[^}]*pointer-events:\s*all/)
  })

  it('handles stay present on the historical read-only node too', () => {
    const wrapper = mountNode(historicalData())
    for (const id of handleIds) {
      expect(wrapper.find(`[id="${id}"]`).exists()).toBe(true)
    }
  })
})
