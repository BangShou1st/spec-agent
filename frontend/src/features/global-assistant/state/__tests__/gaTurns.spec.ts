import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { GaRunProjection, sanitizeGaResourceRefs, useGlobalAssistantStore } from '../globalAssistantStore'
import { groupGaTurns } from '../../presentation/gaTurns'
import ConversationTimeline from '../../components/ConversationTimeline.vue'
import type { GaEventEnvelope, GaMessage } from '../../api/globalAssistant'
import * as api from '../../api/globalAssistant'

function event(run: string, sequence: number, type: string, toolCallId?: string): GaEventEnvelope {
  return { runId: run, threadId: 'thread', sequence, eventId: run + ':' + sequence, type,
    createdAt: '2026-10-02T00:00:01Z', payload: { capabilityId: 'project.search', toolCallId, summary: toolCallId } }
}
function message(run: string, role: 'USER' | 'ASSISTANT'): GaMessage {
  return { id: run + role, runId: run, threadId: 'thread', role, content: run + role, createdAt: '2026-10-02T00:00:00Z' }
}

describe('host-owned turn processes', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks() })
  it('restores bounded source evidence from host events and drops unverified cards', () => {
    const valid = { kind: 'SOURCE', id: '11111111-1111-1111-1111-111111111111', label: 'help:projects:0',
      metadata: { sourceRef: 'help:projects:0', sourceVersion: 'v1', contentHash: 'a'.repeat(64), excerpt: '真实来源正文', startOffset: 0, endOffset: 30, url: 'javascript:alert(1)' } }
    const p = new GaRunProjection()
    p.apply({ ...event('one', 1, 'TOOL_STARTED', 'help'), payload: { capabilityId: 'help.search', toolCallId: 'help' } })
    p.apply({ ...event('one', 2, 'TOOL_COMPLETED', 'help'), payload: { capabilityId: 'help.search', toolCallId: 'help', resourceRefs: [valid, valid], resultCount: 1 } })
    expect(p.activities[0]?.resourceRefs).toHaveLength(1)
    expect(p.activities[0]?.resourceRefs[0]?.metadata).not.toHaveProperty('url')
    expect(p.activities[0]?.resultCount).toBe(1)
    expect(sanitizeGaResourceRefs([{ ...valid, metadata: { ...valid.metadata, contentHash: 'forged' } }])).toEqual([])
  })
  it('associates repeated capabilities by tool call identity, rejects another run and duplicate sequence', () => {
    const p = new GaRunProjection()
    p.apply(event('one', 1, 'TOOL_STARTED', 'a'))
    p.apply(event('one', 2, 'TOOL_STARTED', 'b'))
    p.apply(event('one', 3, 'TOOL_COMPLETED', 'a'))
    expect(p.activities.map(a => [a.toolCallId, a.state])).toEqual([['a', 'success'], ['b', 'running']])
    expect(p.apply(event('two', 4, 'TOOL_COMPLETED', 'b'))).toBe(false)
    expect(p.apply(event('one', 3, 'TOOL_COMPLETED', 'a'))).toBe(false)
    p.apply(event('one', 4, 'RUN_CANCELLED'))
    expect(p.activities[1]?.state).toBe('interrupted')
    expect(p.apply(event('one', 5, 'TOOL_COMPLETED', 'b'))).toBe(false)
  })
  it('does not guess ambiguous historical tool associations', () => {
    const p = new GaRunProjection()
    p.apply(event('one', 1, 'TOOL_STARTED'))
    p.apply(event('one', 2, 'TOOL_STARTED'))
    p.apply(event('one', 3, 'TOOL_COMPLETED'))
    expect(p.activities.map(a => a.state)).toEqual(['running', 'running', 'success'])
    p.apply(event('one', 4, 'RUN_FAILED'))
    expect(p.activities.map(a => a.state)).toEqual(['interrupted', 'interrupted', 'success'])
  })
  it('renders request, its own process, then answer across two rounds and collapses completed processes', async () => {
    const p = new GaRunProjection()
    p.apply(event('one', 1, 'TOOL_STARTED', 'a'))
    p.apply(event('one', 2, 'TOOL_COMPLETED', 'a'))
    const messages = [message('one', 'USER'), message('one', 'ASSISTANT'), message('two', 'USER')]
    const turns = groupGaTurns(messages, [...p.activities, ...p.activities])
    expect(turns.map(t => [t.key, t.activities.length])).toEqual([['one', 1], ['two', 0]])
    const wrapper = mount(ConversationTimeline, { props: { messages, activities: p.activities,
      activeRunId: 'two', streamingText: '本轮正文', currentStatus: '本轮处理', running: true, waitingQuestion: null } })
    const one = wrapper.find('[data-run-id="one"]')
    expect(one.text().indexOf('oneUSER')).toBeLessThan(one.text().indexOf('本轮处理过程'))
    expect(one.text().indexOf('本轮处理过程')).toBeLessThan(one.text().indexOf('oneASSISTANT'))
    expect(one.find('[data-test="ga-process-details"]').attributes('style')).toContain('display: none')
    await one.find('[data-test="ga-process-toggle"]').trigger('click')
    expect(one.find('[data-test="ga-process-details"]').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.find('[data-run-id="two"]').find('[data-test="ga-tool-activity"]').exists()).toBe(false)
    expect(wrapper.find('[data-run-id="two"]').get('[data-test="ga-streaming"]').text()).toContain('本轮正文')
    expect(one.find('[data-test="ga-streaming"]').exists()).toBe(false)
    expect(one.find('[data-test="ga-status"]').exists()).toBe(false)
  })
  it('restores committed history without replaying navigation, draft or withdrawn text', async () => {
    const store = useGlobalAssistantStore()
    store.threadId = 'thread'
    store.messages = [message('one', 'USER'), message('one', 'ASSISTANT')]
    const draft = event('one', 2, 'ANSWER_DELTA'); draft.payload = { generation: 1, text: 'withdrawn' }
    const nav = event('one', 4, 'UI_ACTION'); nav.payload = { destination: 'PROJECTS' }
    const read = vi.spyOn(api, 'listGaEvents').mockResolvedValue([
      event('one', 1, 'TOOL_STARTED', 'a'), draft,
      event('one', 3, 'ANSWER_STREAM_RESET'), nav, event('one', 5, 'RUN_CANCELLED'),
    ])
    await store.restoreProcessHistory()
    await store.restoreProcessHistory()
    expect(read).toHaveBeenCalledTimes(1)
    expect(store.timelineActivities).toHaveLength(1)
    expect(store.timelineActivities[0]?.state).toBe('interrupted')
    expect(store.streamingText).toBe('')
    expect(store.pendingNavigation).toBeNull()
    expect(JSON.stringify(store.historicalActivities)).not.toContain('withdrawn')
  })
  it('ignores a history response after switching threads', async () => {
    const store = useGlobalAssistantStore()
    store.threadId = 'thread'; store.messages = [message('one', 'USER')]
    let release!: (events: GaEventEnvelope[]) => void
    vi.spyOn(api, 'listGaEvents').mockImplementation(() => new Promise(resolve => { release = resolve }))
    const pending = store.restoreProcessHistory()
    store.threadId = 'another'
    release([event('one', 1, 'TOOL_COMPLETED', 'a'), event('one', 2, 'RUN_COMPLETED')])
    await pending
    expect(store.historicalActivities).toEqual({})
  })
})
