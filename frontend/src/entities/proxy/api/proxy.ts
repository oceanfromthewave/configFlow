import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { ProxySettings, SaveProxyPayload } from '@/entities/proxy/model/types'
import { apiFetch, queryKeys } from '@/shared/api'

/** The stored proxy; `url` is null when connections go direct. */
export function useProxySettings() {
  return useQuery({
    queryKey: queryKeys.proxy(),
    queryFn: () => apiFetch<ProxySettings>('/settings/proxy'),
  })
}

/** Saves the proxy and applies it immediately on the backend. */
export function useSaveProxy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: SaveProxyPayload) =>
      apiFetch<ProxySettings>('/settings/proxy', { method: 'PUT', body: payload }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.proxy() }),
  })
}

/** Removes the proxy; connections go direct again. */
export function useDeleteProxy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<ProxySettings>('/settings/proxy', { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.proxy() }),
  })
}
