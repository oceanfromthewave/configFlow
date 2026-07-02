import type { QueryClient } from '@tanstack/react-query'

import {
  OPERATION_AFFECTED_SECTIONS,
  queryKeys,
  SseClient,
  type SseEvent,
} from '@/shared/api'

/**
 * SSE -> TanStack Query invalidation mapping (docs/07 §3):
 * `operation.completed` invalidates `status` / `history` / `refs` / `graph`.
 * Pure function so the mapping is unit-testable without a live EventSource.
 */
export function applySseInvalidation(
  queryClient: QueryClient,
  event: SseEvent,
): void {
  switch (event.type) {
    case 'operation.completed': {
      // The event carries no repositoryId, so invalidate the affected
      // sections across all repositories plus the operations list.
      const sections: readonly string[] = OPERATION_AFFECTED_SECTIONS
      void queryClient.invalidateQueries({
        predicate: (query) => {
          const key = query.queryKey
          return (
            key[0] === 'repositories' &&
            typeof key[2] === 'string' &&
            sections.includes(key[2])
          )
        },
      })
      void queryClient.invalidateQueries({ queryKey: queryKeys.operations() })
      break
    }
    case 'workingtree.changed': {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.status(event.data.repositoryId),
      })
      break
    }
    case 'repository.refs-changed': {
      const { repositoryId } = event.data
      void queryClient.invalidateQueries({
        queryKey: queryKeys.refs(repositoryId),
      })
      void queryClient.invalidateQueries({
        queryKey: queryKeys.graph(repositoryId),
      })
      void queryClient.invalidateQueries({
        queryKey: queryKeys.history(repositoryId),
      })
      break
    }
    case 'operation.progress':
    case 'console.line':
    case 'heartbeat':
      // Streamed into dedicated stores (Operations/Console panels) later;
      // no server-state cache to invalidate.
      break
  }
}

/**
 * Connects the SSE stream and wires every event into query invalidation.
 * Returns a cleanup function (used from an app-level effect).
 */
export function startSseBridge(queryClient: QueryClient): () => void {
  if (typeof EventSource === 'undefined') return () => {} // tests / SSR

  const client = new SseClient()
  const unsubscribe = client.onAny((event) => {
    applySseInvalidation(queryClient, event)
  })
  client.connect()

  return () => {
    unsubscribe()
    client.close()
  }
}
