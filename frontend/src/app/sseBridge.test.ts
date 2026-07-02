import { QueryClient } from '@tanstack/react-query'
import { beforeEach, describe, expect, it } from 'vitest'

import { queryKeys } from '@/shared/api'

import { applySseInvalidation } from './sseBridge'

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
