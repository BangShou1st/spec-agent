import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'

type FetchResponse = {
  ok: boolean
  status: number
  json: () => Promise<unknown>
}

function jsonResponse(body: unknown, status: number): FetchResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('parses a successful JSON response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ id: 'p1', title: 'Visible title' }, 200)),
    )
    const result = await apiClient.get('/projects/p1')
    expect(result).toEqual({ id: 'p1', title: 'Visible title' })
  })

  it('parses the stable API error contract and exposes the safe backend message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            code: 'PROJECT_NOT_FOUND',
            message: 'Project not found',
            timestamp: '2026-01-01T00:00:00Z',
            errors: [],
          },
          404,
        ),
      ),
    )
    const err = (await apiClient.get('/projects/unknown').catch((e: unknown) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('PROJECT_NOT_FOUND')
    expect(err.message).toBe('Project not found')
    expect(err.status).toBe(404)
  })

  it('falls back to the generic message when the body is an HTML error page', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        json: async () => {
          throw new Error('not json')
        },
      } satisfies FetchResponse),
    )
    const err = (await apiClient.get('/x').catch((e: unknown) => e)) as ApiError
    expect(err.message).toBe(GENERIC_ERROR_MESSAGE)
    expect(err.code).toBe('UNKNOWN_ERROR')
  })

  it('never surfaces a JSON body that does not match the API error contract', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          { details: 'raw provider payload that must never reach the UI' },
          500,
        ),
      ),
    )
    const err = (await apiClient.get('/x').catch((e: unknown) => e)) as ApiError
    expect(err.message).toBe(GENERIC_ERROR_MESSAGE)
    expect(err.message).not.toContain('raw provider payload')
  })

  it('preserves provider-neutral backend errors such as rate limiting', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            code: 'MODEL_PROVIDER_RATE_LIMITED',
            message: 'The model provider is temporarily rate limited',
          },
          429,
        ),
      ),
    )
    const err = (await apiClient.get('/x').catch((e: unknown) => e)) as ApiError
    expect(err.code).toBe('MODEL_PROVIDER_RATE_LIMITED')
    expect(err.message).toBe('The model provider is temporarily rate limited')
  })

  it('falls back to a generic message on a network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const err = (await apiClient.get('/x').catch((e: unknown) => e)) as ApiError
    expect(err.message).toBe(GENERIC_ERROR_MESSAGE)
    expect(err.code).toBe('NETWORK_ERROR')
  })

  it('posts a JSON body to the api base and parses the response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 'p9' }, 201))
    vi.stubGlobal('fetch', fetchMock)
    const result = await apiClient.post('/projects', { title: 'New project' })
    expect(result).toEqual({ id: 'p9' })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/projects')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ title: 'New project' })
  })
})