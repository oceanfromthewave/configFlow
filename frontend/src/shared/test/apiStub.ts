import { vi } from 'vitest'

/** One request the component under test sent, captured for assertions. */
export interface RecordedCall {
  url: string
  body: unknown
}

export interface MutationReply {
  status: number
  body?: unknown
}

export interface StubOptions {
  /**
   * Reply to a POST. Defaults to 204 No Content. Pass a function to answer
   * differently per endpoint, e.g. to fail `/unstage` but not `/stage`.
   */
  mutation?: MutationReply | ((url: string) => MutationReply)
}

/**
 * Stubs `fetch` for repository-API widget tests: GETs are answered with `status`,
 * POSTs with `options.mutation`, and every POST is recorded so a test can assert
 * on the request that was actually sent rather than only on the rendered result.
 */
export function stubRepositoryApi(
  status: unknown,
  options: StubOptions = {},
): RecordedCall[] {
  const calls: RecordedCall[] = []
  const reply = options.mutation ?? { status: 204 }

  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        calls.push({
          url,
          body: init.body ? JSON.parse(String(init.body)) : null,
        })
        const mutation = typeof reply === 'function' ? reply(url) : reply
        return Promise.resolve(
          mutation.body === undefined
            ? new Response(null, { status: mutation.status })
            : new Response(JSON.stringify(mutation.body), {
                status: mutation.status,
                headers: { 'content-type': 'application/json' },
              }),
        )
      }
      return Promise.resolve(
        new Response(JSON.stringify(status), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )

  return calls
}
