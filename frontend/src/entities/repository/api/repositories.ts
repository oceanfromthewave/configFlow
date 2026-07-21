import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type {
  RepositorySummary,
  WorkingTreeStatus,
} from '@/entities/repository/model/types'
import { apiFetch, queryKeys } from '@/shared/api'

/** Registered repositories, most recently opened first. */
export function useRepositories() {
  return useQuery({
    queryKey: queryKeys.repositories(),
    queryFn: () => apiFetch<RepositorySummary[]>('/repositories'),
  })
}

/** Registers a local working copy; VCS is auto-detected server-side. */
export function useRegisterRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (localPath: string) =>
      apiFetch<RepositorySummary>('/repositories', {
        method: 'POST',
        body: { localPath },
      }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.repositories() }),
  })
}

/** Marks a repository as opened (updates its last-opened time). */
export function useOpenRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (repositoryId: string) =>
      apiFetch<RepositorySummary>(`/repositories/${repositoryId}/open`, {
        method: 'POST',
      }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.repositories() }),
  })
}

/** Live working-tree status; disabled until a repository is selected. */
export function useWorkingTreeStatus(repositoryId: string | null) {
  return useQuery({
    queryKey: queryKeys.status(repositoryId ?? 'none'),
    queryFn: () =>
      apiFetch<WorkingTreeStatus>(`/repositories/${repositoryId}/status`),
    enabled: repositoryId != null,
  })
}
