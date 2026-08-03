import {
    useInfiniteQuery,
    useMutation,
    useQuery,
    useQueryClient,
} from '@tanstack/react-query'

import type {AcceptedOperation} from '@/entities/operation/model/types'
import type {
    CompareResult,
    FileChange,
    FileDiff,
    HistoryFilters,
    HistoryPage,
    RefList,
    RepositorySummary,
    StashEntry,
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

export interface CreateBranchPayload {
    repositoryId: string
    name: string
    startPoint?: string
    checkout?: boolean
}

export interface DeleteBranchPayload {
    repositoryId: string
    name: string
    remote?: boolean
    force?: boolean
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
        mutationFn: ({repositoryId, ...body}: T & { repositoryId: string }) =>
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
    return useRemoteCommand<{ prune?: boolean }>('fetch')
}

/** Fetch plus integrate into the current branch. */
export function usePull() {
    return useRemoteCommand<{ strategy?: 'MERGE' | 'REBASE' }>('pull')
}

/** Uploads local commits. */
export function usePush() {
    return useRemoteCommand<{ forceWithLease?: boolean; tags?: boolean }>('push')
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

/**
 * Creates a branch, optionally from a specific start point and optionally
 * checking it out right away.
 */
export function useCreateBranch() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, name, startPoint, checkout}: CreateBranchPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/branches`, {
                method: 'POST',
                body: {name, startPoint, checkout},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/**
 * Deletes a branch.
 *
 * The name is sent as-is in the path (branch names may contain slashes, e.g.
 * `feature/x`), matching the backend's `{*name}` wildcard which expects the
 * literal separator rather than a percent-encoded one.
 */
export function useDeleteBranch() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, name, remote, force}: DeleteBranchPayload) => {
            const params = new URLSearchParams()
            if (remote) params.set('remote', 'true')
            if (force) params.set('force', 'true')
            const query = params.toString()
            return apiFetch<AcceptedOperation>(
                `/repositories/${repositoryId}/branches/${name}${query ? `?${query}` : ''}`,
                {method: 'DELETE'},
            )
        },
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

/** Creates a new Git repository at the given path and registers it. */
export function useInitRepository() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: (localPath: string) =>
            apiFetch<RepositorySummary>('/repositories/init', {
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

/** Revisions that separate two refs (branches, tags, or revision ids). */
export function useCompare(
    repositoryId: string | null,
    base: string | null,
    target: string | null,
) {
    return useQuery({
        queryKey: [...queryKeys.history(repositoryId ?? 'none'), 'compare', base, target],
        queryFn: () =>
            apiFetch<CompareResult>(
                `/repositories/${repositoryId}/compare?${new URLSearchParams({base: base ?? '', target: target ?? ''})}`,
            ),
        enabled: repositoryId != null && !!base && !!target,
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

/**
 * The files a commit changed, against its first parent.
 *
 * Keyed under the repository and revision, so selecting another commit fetches
 * fresh and switching repositories does not show a stale list. Disabled until
 * both a repository and a revision are in hand.
 */
export function useCommitChanges(
    repositoryId: string | null,
    revisionId: string | null,
) {
    return useQuery({
        queryKey: [
            ...queryKeys.repository(repositoryId ?? 'none'),
            'commit',
            revisionId ?? 'none',
            'changes',
        ],
        queryFn: () =>
            apiFetch<FileChange[]>(
                `/repositories/${repositoryId}/commits/${revisionId}/changes`,
            ),
        enabled: repositoryId != null && revisionId != null,
    })
}

/**
 * Diff of one file inside a commit, against its first parent.
 *
 * `path` is part of the key so each file in the commit caches its own diff.
 * Disabled until a repository, a revision and a path are all in hand.
 */
export function useCommitFileDiff(
    repositoryId: string | null,
    revisionId: string | null,
    path: string | null,
) {
    return useQuery({
        queryKey: [
            ...queryKeys.repository(repositoryId ?? 'none'),
            'commit',
            revisionId ?? 'none',
            'diff',
            path,
        ],
        queryFn: () =>
            apiFetch<FileDiff>(
                `/repositories/${repositoryId}/commits/${revisionId}/diff?${new URLSearchParams({
                    path: path ?? '',
                })}`,
            ),
        enabled: repositoryId != null && revisionId != null && path != null,
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

export interface CreateTagPayload {
    repositoryId: string
    name: string
    target?: string
    message?: string
}

export interface DeleteTagPayload {
    repositoryId: string
    name: string
}

/**
 * Creates a tag, optionally on a specific revision (defaults to HEAD) and
 * optionally annotated (a non-empty message makes it annotated).
 *
 * Like checkout and merge, this answers as soon as the work is queued; the
 * refreshed refs arrive later over SSE — tags are part of the same `refs`
 * query as branches, so no extra invalidation wiring is needed here.
 */
export function useCreateTag() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, name, target, message}: CreateTagPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/tags`, {
                method: 'POST',
                body: {name, target, message},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/**
 * Deletes a tag.
 *
 * The name is sent as-is in the path (tag names may contain slashes, e.g.
 * `release/1.0`), matching the backend's `{*name}` wildcard.
 */
export function useDeleteTag() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, name}: DeleteTagPayload) => {
            // Slashes are preserved as path separators; everything else in the
            // segment is encoded so a literal `%2F` in a tag name can't be
            // decoded into a spurious separator server-side.
            const encodedName = name.split('/').map(encodeURIComponent).join('/')
            return apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/tags/${encodedName}`, {
                method: 'DELETE',
            })
        },
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

export interface StartRebasePayload {
    repositoryId: string
    upstream: string
}

/**
 * Replays the current branch on top of `upstream`.
 *
 * Like merge, this answers as soon as the work is queued. A rebase that stops on
 * conflicts comes back as a failed operation on the stream; the working tree then
 * reports `rebasing`, which is what surfaces the continue/abort/skip actions.
 */
export function useStartRebase() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, upstream}: StartRebasePayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/rebase`, {
                method: 'POST',
                body: {upstream},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/** Continue / abort / skip all take no body and share one shape. */
function useRebaseStep(step: 'continue' | 'abort' | 'skip') {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId}: {repositoryId: string}) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/rebase/${step}`, {
                method: 'POST',
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

export function useContinueRebase() {
    return useRebaseStep('continue')
}

export function useAbortRebase() {
    return useRebaseStep('abort')
}

export function useSkipRebase() {
    return useRebaseStep('skip')
}

export interface CherryPickPayload {
    repositoryId: string
    revisions: string[]
}

/**
 * Replays the given commits onto the current branch, in the order given.
 *
 * Like rebase, this answers as soon as the work is queued; a conflict comes
 * back as a failed operation on the stream rather than an error here.
 */
export function useCherryPick() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, revisions}: CherryPickPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/cherry-pick`, {
                method: 'POST',
                body: {revisions},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

export interface RevertPayload {
    repositoryId: string
    revisions: string[]
}

/**
 * Records the inverse of the given commits, in the order given, on the current branch.
 *
 * Like cherry-pick, this answers as soon as the work is queued; a conflict comes
 * back as a failed operation on the stream rather than an error here.
 */
export function useRevert() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, revisions}: RevertPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/revert`, {
                method: 'POST',
                body: {revisions},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

export interface ResetPayload {
    repositoryId: string
    target: string
    mode: 'soft' | 'mixed' | 'hard'
}

/**
 * Moves the current branch to `target`; `mode` decides whether the index and
 * working tree follow (`hard` discards uncommitted changes without warning).
 */
export function useReset() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, target, mode}: ResetPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/reset`, {
                method: 'POST',
                body: {target, mode},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
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

export interface SaveStashPayload {
    repositoryId: string
    message?: string
    includeUntracked?: boolean
}

export interface StashIndexPayload {
    repositoryId: string
    index: number
}

/** Stashes of one repository, most recent first (`stash@{0}` is `index: 0`). */
export function useStashes(repositoryId: string | null) {
    return useQuery({
        queryKey: queryKeys.stashes(repositoryId ?? 'none'),
        queryFn: () => apiFetch<StashEntry[]>(`/repositories/${repositoryId}/stashes`),
        enabled: repositoryId != null,
    })
}

/**
 * Saves the working tree as a new stash and reverts it.
 *
 * Like checkout and merge, this answers as soon as the work is queued; the
 * refreshed stash list and working-tree status arrive later over SSE.
 */
export function useSaveStash() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, message, includeUntracked}: SaveStashPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/stashes`, {
                method: 'POST',
                body: {message, includeUntracked},
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/** Applies a stash onto the working tree, keeping the stash entry. */
export function useApplyStash() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, index}: StashIndexPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/stashes/${index}/apply`, {
                method: 'POST',
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/** Applies a stash onto the working tree and drops it on success. */
export function usePopStash() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, index}: StashIndexPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/stashes/${index}/pop`, {
                method: 'POST',
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}

/** Deletes a stash without applying it. */
export function useDropStash() {
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({repositoryId, index}: StashIndexPayload) =>
            apiFetch<AcceptedOperation>(`/repositories/${repositoryId}/stashes/${index}`, {
                method: 'DELETE',
            }),
        onSuccess: () =>
            queryClient.invalidateQueries({queryKey: queryKeys.operations()}),
    })
}