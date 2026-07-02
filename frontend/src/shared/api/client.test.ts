import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiFetch, CLIENT_ERROR_CODES } from './client'

function problemResponse(body: unknown, status = 409): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ApiError parsing (RFC 9457 Problem Details)', () => {
  it('parses code, title, detail, status and context from a problem body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        problemResponse({
          type: 'urn:configflow:error:merge-conflict',
          title: 'Merge resulted in conflicts',
          status: 409,
          detail: '3 files are conflicted',
          code: 'VCS_MERGE_CONFLICT',
          context: { conflictedFiles: ['src/a.ts'] },
        }),
      ),
    )

    const error = await apiFetch('/repositories/x/merge', {
      method: 'POST',
      body: { source: 'feature/x' },
    }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.code).toBe('VCS_MERGE_CONFLICT')
    expect(apiError.title).toBe('Merge resulted in conflicts')
    expect(apiError.detail).toBe('3 files are conflicted')
    expect(apiError.status).toBe(409)
    expect(apiError.context).toEqual({ conflictedFiles: ['src/a.ts'] })
    expect(apiError.message).toBe('3 files are conflicted')
  })

  it('falls back to UNKNOWN_ERROR for non-JSON error responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('Bad Gateway', {
          status: 502,
          headers: { 'content-type': 'text/plain' },
        }),
      ),
    )

    const error = await apiFetch('/health').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.code).toBe(CLIENT_ERROR_CODES.unknown)
    expect(apiError.status).toBe(502)
    expect(apiError.title).toBe('HTTP 502')
  })

  it('wraps network failures into a NETWORK_ERROR ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('fetch failed')),
    )

    const error = await apiFetch('/health').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.code).toBe(CLIENT_ERROR_CODES.network)
    expect(apiError.status).toBe(0)
  })
})

describe('apiFetch request shaping', () => {
  it('adds the X-ConfigFlow-Token header and base URL, parses JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: 'UP' }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await apiFetch<{ status: string }>('/health')

    expect(result).toEqual({ status: 'UP' })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:8465/api/v1/health')
    expect(
      (init.headers as Record<string, string>)['X-ConfigFlow-Token'],
    ).toBe('dev-token')
  })
})
