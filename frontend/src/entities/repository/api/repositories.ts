import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'

import type {
    RepositorySummary,
    WorkingTreeStatus,
} from '@/entities/repository/model/types'
import {apiFetch, queryKeys} from '@/shared/api'

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
                body: {localPath},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.repositories()}),
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
            queryClient.invalidateQueries({queryKey: queryKeys.repositories()}),
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

interface PathsPayload {
    repositoryId: string
    paths: string[]
}

/** Adds the given paths to the staging area. */
export function useStageFiles() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, paths}: PathsPayload) =>
            apiFetch<void>(`/repositories/${repositoryId}/stage`, {
                method: 'POST',
                body: {paths},
            }),
        onSuccess: (_result, {repositoryId}) =>
            queryClient.invalidateQueries({queryKey: queryKeys.status(repositoryId)}),
    })
}

/** Removes the given paths from the staging area. */
export function useUnstageFiles() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, paths}: PathsPayload) =>
            apiFetch<void>(`/repositories/${repositoryId}/unstage`, {
                method: 'POST',
                body: {paths},
            }),
        onSuccess: (_result, {repositoryId}) =>
            queryClient.invalidateQueries({queryKey: queryKeys.status(repositoryId)}),
    })
}

export interface CommitPayload {
    repositoryId: string
    message: string
    amend?: boolean
}

export interface CommitResult {
    revisionId: string
}

/** Commits what is currently staged; history changes too, so both caches drop. */
export function useCommit() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, message, amend = false}: CommitPayload) =>
            apiFetch<CommitResult>(`/repositories/${repositoryId}/commit`, {
                method: 'POST',
                body: {message, amend},
            }),
        onSuccess: (_result, {repositoryId}) => {
            queryClient.invalidateQueries({queryKey: queryKeys.status(repositoryId)})
            queryClient.invalidateQueries({queryKey: queryKeys.history(repositoryId)})
        },
    })
}

