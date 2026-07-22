import {
    useInfiniteQuery,
    useMutation,
    useQuery,
    useQueryClient,
} from '@tanstack/react-query'

import type {
    HistoryFilters,
    HistoryPage,
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

/** How many revisions one page holds; the server caps this at 200. */
export const HISTORY_PAGE_SIZE = 50

/** Drops empty filters so a blank search box does not narrow the query. */
function historySearchParams(filters: HistoryFilters, cursor?: string) {
    const params = new URLSearchParams({limit: String(HISTORY_PAGE_SIZE)})
    if (cursor) {
        params.set('cursor', cursor)
    }
    for (const [key, value] of Object.entries(filters)) {
        if (value != null && value.trim() !== '') {
            params.set(key, value.trim())
        }
    }
    return params
}

/**
 * History, newest first, one cursor page at a time.
 *
 * History is unbounded, so it is never fetched whole: the server returns the id
 * of the first revision that did not fit and the next page resumes there.
 */
export function useHistory(
    repositoryId: string | null,
    filters: HistoryFilters = {},
) {
    return useInfiniteQuery({
        queryKey: [...queryKeys.history(repositoryId ?? 'none'), filters],
        queryFn: ({pageParam}) =>
            apiFetch<HistoryPage>(
                `/repositories/${repositoryId}/history?${historySearchParams(filters, pageParam)}`,
            ),
        initialPageParam: undefined as string | undefined,
        getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
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

