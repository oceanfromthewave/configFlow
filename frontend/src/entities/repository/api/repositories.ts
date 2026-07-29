import {
    useInfiniteQuery,
    useMutation,
    useQuery,
    useQueryClient,
} from '@tanstack/react-query'

import type {AcceptedOperation} from '@/entities/operation/model/types'
import type {
    FileDiff,
    HistoryFilters,
    HistoryPage,
    RefList,
    RepositorySummary,
    WorkingTreeStatus,
} from '@/entities/repository/model/types'
import {apiFetch, queryKeys} from '@/shared/api'

interface CheckoutPayload {
    repositoryId: string
    ref: string
}

interface MergePayload {
    repositoryId: string
    source: string
    fastForwardOnly?: boolean
    squash?: boolean
}

/**
 * Runs one of the remote commands.
 *
 * All three answer as soon as the work is queued. Progress arrives over SSE and
 * the refreshed refs and file list follow from `operation.completed`, so there
 * is nothing to do here beyond making the queue visible immediately.
 */
function useRemoteCommand<T>(command: 'fetch' | 'pull' | 'push') {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, ...body}: T & {repositoryId: string}) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/${command}`, {
                method: 'POST',
                body,
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/** Downloads new revisions without touching the working tree. */
export function useFetch() {
    return useRemoteCommand<{prune?: boolean}>('fetch')
}

/** Fetch plus integrate into the current branch. */
export function usePull() {
    return useRemoteCommand<{strategy?: 'MERGE' | 'REBASE'}>('pull')
}

/** Uploads local commits. */
export function usePush() {
    return useRemoteCommand<{forceWithLease?: boolean; tags?: boolean}>('push')
}

export interface ClonePayload {
    url: string
    localPath: string
    vcsType?: 'GIT' | 'SVN'
}

/**
 * Clones into a new directory and registers the result.
 *
 * The repository list refreshes from `repository.registered`, not from here:
 * this call returns as soon as the clone is queued, which is long before there
 * is anything to list.
 */
export function useClone() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (payload: ClonePayload) =>
            apiFetch<AcceptedOperation>('/repositories/clone', {
                method: 'POST',
                body: payload,
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/**
 * Guesses the directory name Git would use for a clone URL.
 *
 * Handles both `https://host/owner/repo.git` and `git@host:owner/repo.git`,
 * and returns null when there is nothing sensible to derive.
 */
export function repositoryNameFromUrl(url: string): string | null {
    // Drop any query and fragment before anything else. `repo.git?ref=main` would
    // otherwise keep its `.git` and carry a `?` into the directory name, which is not
    // a legal path character on Windows.
    const trimmed = url.trim().split(/[?#]/)[0].replace(/\/+$/, '')
    if (trimmed === '') return null
    const lastSegment = trimmed.split(/[/:]/).pop()
    if (!lastSegment) return null
    const name = lastSegment.replace(/\.git$/i, '')
    return name === '' ? null : name
}

/**
 * Switches the working tree to another ref.
 *
 * Answers as soon as the work is queued, not when it finishes: the refreshed
 * refs and file list arrive later through `operation.completed` on the SSE
 * stream, which the bridge turns into cache invalidation.
 */
export function useCheckout() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, ref}: CheckoutPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/checkout`, {
                method: 'POST',
                body: {ref},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/**
 * Merges another ref into the current branch.
 *
 * Like checkout, this answers as soon as the work is queued; the refreshed refs
 * and file list arrive later over SSE. A content conflict comes back as a failed
 * operation on the stream, not as an error thrown here.
 */
export function useMerge() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, source, fastForwardOnly, squash}: MergePayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/merge`, {
                method: 'POST',
                body: {source, fastForwardOnly, squash},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

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

/**
 * Diff of one working-copy file.
 *
 * `staged` is part of the key, not just the request: the same path has two
 * different diffs at once (HEAD vs index, index vs working tree).
 */
export function useFileDiff(
    repositoryId: string | null,
    path: string | null,
    staged: boolean,
) {
    return useQuery({
        queryKey: [...queryKeys.repository(repositoryId ?? 'none'), 'diff', path, staged],
        queryFn: () =>
            apiFetch<FileDiff>(
                `/repositories/${repositoryId}/diff?${new URLSearchParams({
                    path: path ?? '',
                    staged: String(staged),
                })}`,
            ),
        enabled: repositoryId != null && path != null,
    })
}

/** Branches, tags and the HEAD pointer of one repository. */
export function useRefs(repositoryId: string | null) {
    return useQuery({
        queryKey: queryKeys.refs(repositoryId ?? 'none'),
        queryFn: () => apiFetch<RefList>(`/repositories/${repositoryId}/refs`),
        enabled: repositoryId != null,
    })
}

interface PathsPayload {
    repositoryId: string
    paths: string[]
}

/**
 * Everything read from one repository is keyed under its id, so dropping that
 * prefix refreshes the status, the open diff and the history in one go. Staging
 * moves a change between the two diff sides, so invalidating only the status
 * would leave the visible diff describing the previous side.
 */
function invalidateRepository(
    queryClient: ReturnType<typeof useQueryClient>,
    repositoryId: string,
) {
    return queryClient.invalidateQueries({
        queryKey: queryKeys.repository(repositoryId),
    })
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
            invalidateRepository(queryClient, repositoryId),
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
            invalidateRepository(queryClient, repositoryId),
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

/** Commits what is currently staged; the index, the diffs and the history all move. */
export function useCommit() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, message, amend = false}: CommitPayload) =>
            apiFetch<CommitResult>(`/repositories/${repositoryId}/commit`, {
                method: 'POST',
                body: {message, amend},
            }),
        onSuccess: (_result, {repositoryId}) =>
            invalidateRepository(queryClient, repositoryId),
    })
}

