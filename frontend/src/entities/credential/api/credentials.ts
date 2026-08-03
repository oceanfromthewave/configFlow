import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type {
  CreateSshKeyPayload,
  Credential,
  SaveCredentialPayload,
} from '@/entities/credential/model/types'
import { apiFetch, queryKeys } from '@/shared/api'

/** Every registered credential; none of them carries a secret. */
export function useCredentials() {
  return useQuery({
    queryKey: queryKeys.credentials(),
    queryFn: () => apiFetch<Credential[]>('/credentials'),
  })
}

/**
 * Registers a credential, replacing whatever was stored for the same account.
 *
 * The server rotates in place — re-entering a host/username pair overwrites the
 * old secret rather than adding a row — so the list simply refreshes on success.
 */
export function useSaveCredential() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: SaveCredentialPayload) =>
      apiFetch<Credential>('/credentials', { method: 'POST', body: payload }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials() }),
  })
}

/**
 * Generates an SSH key for a host and returns it — including the public half, which is
 * the point: the user needs that line to paste into the server.
 */
export function useCreateSshKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: CreateSshKeyPayload) =>
      apiFetch<Credential>('/credentials/ssh-keys', { method: 'POST', body: payload }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials() }),
  })
}

/** Removes a credential from both the OS store and the reference table. */
export function useDeleteCredential() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/credentials/${id}`, { method: 'DELETE' }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials() }),
  })
}
