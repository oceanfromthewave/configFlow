import { useQuery } from '@tanstack/react-query'

import { apiFetch } from './client'
import { queryKeys } from './queryKeys'

export interface HealthResponse {
  status: string
}

/**
 * Backend liveness probe (`GET /health`), surfaced in the StatusBar.
 * Polls so the indicator recovers when the backend comes back up.
 */
export function useHealth() {
  return useQuery({
    queryKey: queryKeys.health(),
    queryFn: () => apiFetch<HealthResponse>('/health'),
    refetchInterval: 15_000,
    retry: false,
    staleTime: 10_000,
  })
}
