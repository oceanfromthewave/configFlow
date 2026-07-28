import { QueryClient } from '@tanstack/react-query'
import { beforeEach, describe, expect, it } from 'vitest'

import { queryKeys, type SseEvent } from '@/shared/api'

import { applySseInvalidation, authPromptFromEvent } from './sseBridge'

const REPO_A = 'repo-a'
const REPO_B = 'repo-b'

describe('applySseInvalidation (docs/07 §3 mapping)', () => {
  let queryClient: QueryClient

  const seed = (key: readonly unknown[]) => {
    queryClient.setQueryData(key, {})
  }
  const isInvalidated = (key: readonly unknown[]) =>
    queryClient.getQueryState(key)?.isInvalidated === true

  beforeEach(() => {
    queryClient = new QueryClient()
    seed(queryKeys.status(REPO_A))
    seed(queryKeys.history(REPO_A))
    seed(queryKeys.refs(REPO_A))
    seed(queryKeys.graph(REPO_A))
    seed(queryKeys.status(REPO_B))
    seed(queryKeys.repositories())
    seed(queryKeys.operations())
    seed(queryKeys.health())
  })

  it('operation.completed invalidates status/history/refs/graph and operations', () => {
    applySseInvalidation(queryClient, {
      type: 'operation.completed',
      data: {
        operationId: 'op-1',
        state: 'SUCCEEDED',
        error: null,
        result: { conflicted: false },
      },
    })

    expect(isInvalidated(queryKeys.status(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.history(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.refs(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.graph(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.status(REPO_B))).toBe(true)
    expect(isInvalidated(queryKeys.operations())).toBe(true)
    // Unrelated caches stay fresh.
    expect(isInvalidated(queryKeys.repositories())).toBe(false)
    expect(isInvalidated(queryKeys.health())).toBe(false)
  })

  it('workingtree.changed invalidates only that repository status', () => {
    applySseInvalidation(queryClient, {
      type: 'workingtree.changed',
      data: { repositoryId: REPO_A },
    })

    expect(isInvalidated(queryKeys.status(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.status(REPO_B))).toBe(false)
    expect(isInvalidated(queryKeys.history(REPO_A))).toBe(false)
  })

  it('repository.refs-changed invalidates refs, graph and history of that repository', () => {
    applySseInvalidation(queryClient, {
      type: 'repository.refs-changed',
      data: { repositoryId: REPO_A },
    })

    expect(isInvalidated(queryKeys.refs(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.graph(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.history(REPO_A))).toBe(true)
    expect(isInvalidated(queryKeys.status(REPO_A))).toBe(false)
  })

  it('heartbeat and console.line do not invalidate anything', () => {
    applySseInvalidation(queryClient, { type: 'heartbeat', data: {} })
    applySseInvalidation(queryClient, {
      type: 'console.line',
      data: { repositoryId: REPO_A, line: 'git fetch origin', level: 'cmd' },
    })

    const allFresh = queryClient
      .getQueryCache()
      .getAll()
      .every((query) => !query.state.isInvalidated)
    expect(allFresh).toBe(true)
  })
})

describe('authPromptFromEvent (VCS_AUTH_REQUIRED → credential prompt)', () => {
  const completed = (
    error: { code: string; context?: Record<string, string> } | null,
    state: 'SUCCEEDED' | 'FAILED' | 'CANCELLED' = 'FAILED',
  ): SseEvent => ({
    type: 'operation.completed',
    data: { operationId: 'op-1', state, error },
  })

  it('raises a prompt for the failing remote', () => {
    expect(
      authPromptFromEvent(
        completed({
          code: 'VCS_AUTH_REQUIRED',
          context: { host: 'github.com', protocol: 'https' },
        }),
      ),
    ).toEqual({ operationId: 'op-1', host: 'github.com', protocol: 'https' })
  })

  it('defaults the protocol to https when the failure omits it', () => {
    // The modal builds `protocol://host`; a blank protocol would read oddly.
    expect(
      authPromptFromEvent(
        completed({ code: 'VCS_AUTH_REQUIRED', context: { host: 'example.com' } }),
      ),
    ).toEqual({ operationId: 'op-1', host: 'example.com', protocol: 'https' })
  })

  it('stays silent when there is no host to attach a credential to', () => {
    expect(
      authPromptFromEvent(completed({ code: 'VCS_AUTH_REQUIRED', context: {} })),
    ).toBeNull()
    expect(authPromptFromEvent(completed({ code: 'VCS_AUTH_REQUIRED' }))).toBeNull()
  })

  it('ignores other failures, successes and unrelated events', () => {
    expect(
      authPromptFromEvent(
        completed({ code: 'VCS_NETWORK_ERROR', context: { host: 'github.com' } }),
      ),
    ).toBeNull()
    // A success carrying context (it never should) must still not prompt.
    expect(
      authPromptFromEvent(
        completed(
          { code: 'VCS_AUTH_REQUIRED', context: { host: 'github.com' } },
          'SUCCEEDED',
        ),
      ),
    ).toBeNull()
    expect(authPromptFromEvent(completed(null))).toBeNull()
    expect(
      authPromptFromEvent({ type: 'workingtree.changed', data: { repositoryId: REPO_A } }),
    ).toBeNull()
  })
})
