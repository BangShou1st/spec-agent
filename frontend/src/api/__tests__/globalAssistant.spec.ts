import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import {
  buildGaUiContext,
  cancelGaRun,
  createGaRun,
  createGaThread,
  gaUiActionToRoute,
  getGaRun,
  getGaThread,
  isGaTerminalEventType,
  listGaEvents,
  listGaMessages,
} from '@/api/globalAssistant'
import { parseGaEnvelope, parseSseBuffer } from '@/api/globalAssistantEvents'

function jsonOk(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

describe('global assistant api', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('creates a thread', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonOk({ threadId: 't-1' }, 201))
    vi.stubGlobal('fetch', fetchMock)
    await expect(createGaThread()).resolves.toEqual({ threadId: 't-1' })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/global-assistant/threads')
  })

  it('creates a run with message and ui context', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonOk({ runId: 'r-1', status: 'RUNNING' }))
    vi.stubGlobal('fetch', fetchMock)
    const result = await createGaRun('t-1', 'hello', { currentPage: 'PROJECTS', selectedEntity: null })
    expect(result.runId).toBe('r-1')
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toMatchObject({ message: 'hello' })
  })

  it('loads thread, messages, run, events and cancels', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonOk({ threadId: 't-1', summary: '', summaryVersion: 1, workingStateVersion: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }))
      .mockResolvedValueOnce(jsonOk([{ id: 'm-1', threadId: 't-1', role: 'USER', content: 'hi', runId: null, createdAt: '2026-01-01T00:00:00Z' }]))
      .mockResolvedValueOnce(jsonOk({ runId: 'r-1', threadId: 't-1', status: 'RUNNING', stepCount: 1, cancelRequestedAt: null, startedAt: '2026-01-01T00:00:00Z', completedAt: null, errorCode: null }))
      .mockResolvedValueOnce(jsonOk([{ eventId: 'e-1', runId: 'r-1', threadId: 't-1', type: 'STATUS', sequence: 1, createdAt: '2026-01-01T00:00:00Z', payload: { message: 'hi' } }]))
      .mockResolvedValueOnce(jsonOk({ runId: 'r-1', threadId: 't-1', status: 'CANCELLED', stepCount: 1, cancelRequestedAt: '2026-01-01T00:00:00Z', startedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:00:00Z', errorCode: null }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(getGaThread('t-1')).resolves.toMatchObject({ threadId: 't-1' })
    await expect(listGaMessages('t-1')).resolves.toHaveLength(1)
    await expect(getGaRun('r-1')).resolves.toMatchObject({ status: 'RUNNING' })
    await expect(listGaEvents('r-1')).resolves.toHaveLength(1)
    await expect(cancelGaRun('r-1')).resolves.toMatchObject({ status: 'CANCELLED' })
  })

  it('surfaces typed ApiError without leaking raw bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonOk({ code: 'GLOBAL_ASSISTANT_RUN_ACTIVE', message: 'Thread already hosts an active run' }, 409)))
    const err = await createGaRun('t-1', 'hi', { currentPage: 'PROJECTS', selectedEntity: null }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe('GLOBAL_ASSISTANT_RUN_ACTIVE')
  })

  it('projects ui context deterministically from router state', () => {
    expect(buildGaUiContext({ path: '/projects', params: {} }).currentPage).toBe('PROJECTS')
    expect(buildGaUiContext({ path: '/projects/abc', params: { projectId: 'abc' } })).toMatchObject({
      currentPage: 'PROJECT',
      selectedEntity: { type: 'PROJECT', id: 'abc' },
    })
    expect(buildGaUiContext({ path: '/settings/models', params: {} }).currentPage).toBe('SETTINGS')
    expect(buildGaUiContext({ path: '/settings/skills', params: {} }).currentPage).toBe('SKILLS')
    expect(buildGaUiContext({ path: '/settings/connections/x', params: {} }).currentPage).toBe('CONNECTIONS')
    expect(buildGaUiContext({ path: '/unknown', params: {} }).currentPage).toBe('UNKNOWN')
  })

  it('maps typed UI actions to routes and rejects arbitrary urls', () => {
    expect(gaUiActionToRoute('PROJECT', 'pid-1')).toBe('/projects/pid-1')
    expect(gaUiActionToRoute('PROJECT', null)).toBeNull()
    expect(gaUiActionToRoute('PROJECTS')).toBe('/projects')
    expect(gaUiActionToRoute('SKILLS')).toBe('/settings/skills')
    expect(gaUiActionToRoute('CONNECTIONS')).toBe('/settings/connections')
    expect(gaUiActionToRoute('SETTINGS')).toBe('/settings')
    expect(gaUiActionToRoute('EVIL', 'https://evil.example')).toBeNull()
  })

  it('parses SSE frames and envelopes without crashing on unknown input', () => {
    const envelope = { eventId: 'e-1', runId: 'r-1', threadId: 't-1', type: 'STATUS', sequence: 1, createdAt: '2026-01-01T00:00:00Z', payload: { message: 'hi' } }
    const raw = 'id: 1\nevent: STATUS\ndata: ' + JSON.stringify(envelope) + '\n\n'
    const parsed = parseSseBuffer(raw)
    expect(parsed.events).toHaveLength(1)
    expect(parseGaEnvelope(parsed.events[0].data)).toMatchObject({ type: 'STATUS', sequence: 1 })
    expect(parseGaEnvelope('not-json')).toBeNull()
    expect(isGaTerminalEventType('RUN_COMPLETED')).toBe(true)
    expect(isGaTerminalEventType('STATUS')).toBe(false)
  })
})

