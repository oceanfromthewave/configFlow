import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { Operation } from '@/entities/operation/model/types'
import { isTerminal } from '@/entities/operation/model/types'
import { apiFetch, queryKeys } from '@/shared/api'

/**
 * Operations of a repository, newest first.
 *
 * Progress arrives over SSE, but a queued operation that never reports anything
 * would otherwise sit at its accepted state forever, so this polls slowly while
 * something is still running and stops as soon as everything settles.
 */
export function useOperations(repositoryId: string | null) {
  return useQuery({
    queryKey: [...queryKeys.operations(), repositoryId ?? 'all'],
    queryFn: () =>
      apiFetch<Operation[]>(
        repositoryId == null
          ? '/operations'
          : `/operations?${new URLSearchParams({ repositoryId })}`,
      ),
    refetchInterval: (query) => {
      const operations = query.state.data ?? []
      const busy = operations.some((operation) => !isTerminal(operation.state))
      return busy ? 2_000 : false
    },
  })
}

/**
 * Asks an operation to stop.
 *
 * Cancellation is cooperative server-side, so a success here means the request
 * was recorded — not that the work has already unwound.
 */
export function useCancelOperation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (operationId: string) =>
      apiFetch<void>(`/operations/${operationId}/cancel`, { method: 'POST' }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.operations() }),
  })
}

/**
 * Retries a failed operation by re-running it as a fresh one.
 *
 * The server mints a new operation id rather than reviving the old record, and
 * re-running re-resolves credentials — so this is what picks up a credential
 * just saved for the remote that rejected it. On success the list refreshes to
 * show the new operation.
 */
export function useRetryOperation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (operationId: string) =>
      apiFetch<Operation>(`/operations/${operationId}/retry`, { method: 'POST' }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.operations() }),
  })
}
