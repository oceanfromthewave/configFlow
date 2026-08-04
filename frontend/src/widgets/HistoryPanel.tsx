import {
    useEffect,
    useMemo,
    useRef,
    useState,
    type FormEvent,
} from 'react'

import {useCherryPick, useCompare, useHistory, useRefs, useRevert} from '@/entities/repository/api/repositories'
import {computeCommitGraph, type RowGraph} from '@/entities/repository/lib/commitGraph'
import {CommitDetail} from '@/widgets/CommitDetail'
import type {
    HistoryFilters,
    RefLabel,
    RefLabelKind,
    Revision,
} from '@/entities/repository/model/types'
import {useI18n, useT} from '@/shared/i18n'
import {apiErrorKey} from '@/shared/lib/apiErrorMessage'
import {useUiStore} from '@/shared/lib/uiStore'
import {Badge, Button, EmptyState, Spinner} from '@/shared/ui'
import type {BadgeVariant} from '@/shared/ui'

/** Ref decorations are colour-coded so branches read differently from tags. */
const LABEL_VARIANT: Record<RefLabelKind, BadgeVariant> = {
    HEAD: 'accent',
    BRANCH: 'added',
    REMOTE_BRANCH: 'modified',
    TAG: 'renamed',
}

/** Graph geometry; ROW_HEIGHT must match the commit row's fixed height. */
const LANE_WIDTH = 14
const ROW_HEIGHT = 28
const DOT_RADIUS = 3.5

// Cycled by lane so neighbouring branches stay distinct; each holds up in both
// the light and dark themes.
const LANE_COLORS = [
    '#3b82f6', '#22c55e', '#a855f7', '#f59e0b',
    '#ec4899', '#14b8a6', '#ef4444', '#8b5cf6',
]

function laneX(lane: number) {
    return lane * LANE_WIDTH + LANE_WIDTH / 2
}

function laneColor(index: number) {
    return LANE_COLORS[index % LANE_COLORS.length]
}

/**
 * The graph gutter for one commit row: straight verticals for lanes passing
 * through, converging lines from children above, and the node's own lines down
 * to its parents. A constant width keeps every row's nodes vertically aligned.
 */
function GraphColumn({row, laneCount}: { row?: RowGraph; laneCount: number }) {
    const width = Math.max(laneCount, 1) * LANE_WIDTH
    if (!row) {
        return <svg width={width} height={ROW_HEIGHT} className="shrink-0" aria-hidden="true"/>
    }
    const centerX = laneX(row.nodeLane)
    const midY = ROW_HEIGHT / 2
    return (
        <svg
            width={width}
            height={ROW_HEIGHT}
            viewBox={`0 0 ${width} ${ROW_HEIGHT}`}
            className="shrink-0"
            aria-hidden="true"
        >
            {row.passThrough.map((edge) => (
                <line
                    key={`p${edge.fromLane}`}
                    x1={laneX(edge.fromLane)} y1={0}
                    x2={laneX(edge.fromLane)} y2={ROW_HEIGHT}
                    stroke={laneColor(edge.color)} strokeWidth={1.5}
                />
            ))}
            {row.incoming.map((edge) => (
                <line
                    key={`i${edge.fromLane}`}
                    x1={laneX(edge.fromLane)} y1={0}
                    x2={centerX} y2={midY}
                    stroke={laneColor(edge.color)} strokeWidth={1.5}
                />
            ))}
            {row.outgoing.map((edge) => (
                <line
                    key={`o${edge.toLane}`}
                    x1={centerX} y1={midY}
                    x2={laneX(edge.toLane)} y2={ROW_HEIGHT}
                    stroke={laneColor(edge.color)} strokeWidth={1.5}
                />
            ))}
            <circle cx={centerX} cy={midY} r={DOT_RADIUS} fill={laneColor(row.color)}/>
        </svg>
    )
}

/** Git stores the full message; the list shows only its subject line. */
function subjectOf(message: string) {
    const firstLine = message.split('\n', 1)[0]
    return firstLine.trim() || message.trim()
}

function RefLabels({labels}: { labels: RefLabel[] }) {
    if (labels.length === 0) return null
    return (
        <span className="flex shrink-0 items-center gap-1">
            {labels.map((label) => (
                <Badge key={`${label.kind}:${label.name}`} variant={LABEL_VARIANT[label.kind]}>
                    {label.kind === 'HEAD' ? 'HEAD' : label.name}
                </Badge>
            ))}
        </span>
    )
}

function CommitRow({
                       revision,
                       formatDate,
                       graph,
                       laneCount,
                       selected,
                       onSelect,
                       onOpenMenu,
                   }: {
    revision: Revision
    formatDate: (iso: string) => string
    graph?: RowGraph
    laneCount: number
    selected: boolean
    onSelect: () => void
    onOpenMenu: (position: { x: number; y: number }, target: HTMLElement) => void
}) {
    return (
        <li
            role="option"
            tabIndex={0}
            aria-selected={selected}
            aria-haspopup="menu"
            className={`flex cursor-pointer items-center gap-2 rounded px-2 outline-none focus-visible:ring-2 focus-visible:ring-accent/60 ${
                selected ? 'bg-elevated ring-1 ring-accent/50' : 'hover:bg-elevated'
            }`}
            style={{height: ROW_HEIGHT}}
            onClick={onSelect}
            onContextMenu={(event) => {
                event.preventDefault()
                onOpenMenu({x: event.clientX, y: event.clientY}, event.currentTarget)
            }}
            onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    onSelect()
                    return
                }
                // The mouse isn't the only way to ask for this row's menu: the dedicated
                // Menu key, and Shift+F10 on keyboards without one, both mean the same thing.
                if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
                    event.preventDefault()
                    const rect = event.currentTarget.getBoundingClientRect()
                    onOpenMenu({x: rect.left, y: rect.bottom}, event.currentTarget)
                }
            }}
        >
            <GraphColumn row={graph} laneCount={laneCount}/>
            <RefLabels labels={revision.labels}/>
            <span
                className="min-w-0 flex-1 truncate text-xs text-primary"
                title={revision.message}
            >
                {subjectOf(revision.message)}
            </span>
            <span className="shrink-0 truncate text-[11px] text-muted" title={revision.author.email ?? undefined}>
                {revision.author.name}
            </span>
            <span className="shrink-0 font-mono text-[11px] text-muted">
                {revision.id.slice(0, 7)}
            </span>
            <time className="shrink-0 text-[11px] text-muted" dateTime={revision.timestamp}>
                {formatDate(revision.timestamp)}
            </time>
        </li>
    )
}

/** History tab (docs/06 §2): cursor-paged commit list with author/message filters. */
export function HistoryPanel() {
    const t = useT()
    const {locale} = useI18n()
    const repositoryId = useUiStore((s) => s.currentRepositoryId)
    const requestConfirm = useUiStore((s) => s.requestConfirm)

    // Draft vs applied: typing must not refire the query on every keystroke.
    const [draft, setDraft] = useState<HistoryFilters>({author: '', message: '', branch: ''})
    const [filters, setFilters] = useState<HistoryFilters>({})
    const [selectedId, setSelectedId] = useState<string | null>(null)

    // Compare mode swaps the commit source: two refs' divergence instead of the
    // filtered history feed. Its own base/target state, independent of filters.
    const [compareOpen, setCompareOpen] = useState(false)
    const [compareBase, setCompareBase] = useState('')
    const [compareTarget, setCompareTarget] = useState('')

    const history = useHistory(repositoryId, filters)
    const refs = useRefs(repositoryId)
    const compare = useCompare(repositoryId, compareOpen ? compareBase : null, compareOpen ? compareTarget : null)
    const cherryPick = useCherryPick()
    const revert = useRevert()

    const branchNames = useMemo(
        () => (refs.data?.refs ?? [])
            .filter((ref) => ref.kind === 'BRANCH' || ref.kind === 'REMOTE_BRANCH')
            .map((ref) => ref.name),
        [refs.data],
    )

    // The commit a right-click opened the context menu on, and where to anchor it.
    const [commitMenu, setCommitMenu] = useState<
        { revisionId: string; x: number; y: number } | null
    >(null)
    const commitTriggerRef = useRef<HTMLElement | null>(null)
    const commitMenuItemRef = useRef<HTMLButtonElement | null>(null)

    function closeCommitMenu() {
        setCommitMenu(null)
        commitTriggerRef.current?.focus()
        commitTriggerRef.current = null
    }

    // Switching repositories (not just closing one) must drop any open menu: its
    // revisionId belongs to the previous repository and would otherwise be sent
    // alongside the new one's repositoryId.
    useEffect(() => {
        setCommitMenu(null)
        commitTriggerRef.current = null
    }, [repositoryId])

    useEffect(() => {
        if (commitMenu == null) return
        const close = () => closeCommitMenu()
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') close()
        }
        window.addEventListener('keydown', onKey)
        window.addEventListener('scroll', close, true)
        return () => {
            window.removeEventListener('keydown', onKey)
            window.removeEventListener('scroll', close, true)
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [commitMenu])

    useEffect(() => {
        if (commitMenu != null) commitMenuItemRef.current?.focus()
    }, [commitMenu])

    function openCommitMenu(
        revisionId: string,
        position: { x: number; y: number },
        target: HTMLElement,
    ) {
        commitTriggerRef.current = target
        setCommitMenu({revisionId, ...position})
    }

    async function runCherryPick() {
        if (commitMenu == null || repositoryId == null) return
        const {revisionId} = commitMenu
        if (!(await requestConfirm(t('cherryPick.confirm')))) return
        cherryPick.mutate({repositoryId, revisions: [revisionId]})
        closeCommitMenu()
    }

    async function runRevert() {
        if (commitMenu == null || repositoryId == null) return
        const {revisionId} = commitMenu
        if (!(await requestConfirm(t('revert.confirm')))) return
        revert.mutate({repositoryId, revisions: [revisionId]})
        closeCommitMenu()
    }

    const formatDate = useMemo(() => {
        const format = new Intl.DateTimeFormat(locale, {
            dateStyle: 'medium',
            timeStyle: 'short',
        })
        return (iso: string) => format.format(new Date(iso))
    }, [locale])

    // Flatten the loaded pages and lay out the graph together, keyed on the query
    // data: paging in more commits recomputes, but unrelated renders (typing into
    // a filter box) reuse the previous layout.
    const {revisions, graph} = useMemo(() => {
        const items = compareOpen
            ? compare.data?.revisions ?? []
            : history.data?.pages.flatMap((page) => page.items) ?? []
        return {revisions: items, graph: computeCommitGraph(items)}
    }, [history.data, compareOpen, compare.data])

    if (repositoryId == null) {
        return (
            <EmptyState
                title={t('center.emptyTitle')}
                description={t('center.emptyDescription')}
            />
        )
    }

    function applyFilters(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        setFilters({author: draft.author, branch: draft.branch, message: draft.message})
    }

    function clearFilters() {
        setDraft({author: '', message: '', branch: ''})
        setFilters({})
    }

    const hasDraft = Boolean(draft.author?.trim() || draft.branch?.trim() || draft.message?.trim())
    // The selection is held by id; if that commit is no longer in the loaded set
    // (a filter narrowed it away), the detail pane falls back to its empty hint.
    const selected = revisions.find((revision) => revision.id === selectedId) ?? null

    return (
        <div className="flex h-full flex-col">
            {compareOpen ? (
                <div className="flex shrink-0 items-center gap-2 border-b border-border p-2">
                    <input
                        list="history-compare-refs"
                        value={compareBase}
                        onChange={(event) => setCompareBase(event.target.value)}
                        placeholder={t('history.compareBase')}
                        aria-label={t('history.compareBase')}
                        className="w-36 rounded-md border border-border bg-elevated px-2 py-1 text-xs text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                    />
                    <input
                        list="history-compare-refs"
                        value={compareTarget}
                        onChange={(event) => setCompareTarget(event.target.value)}
                        placeholder={t('history.compareTarget')}
                        aria-label={t('history.compareTarget')}
                        className="w-36 rounded-md border border-border bg-elevated px-2 py-1 text-xs text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                    />
                    <datalist id="history-compare-refs">
                        {branchNames.map((name) => (
                            <option key={name} value={name}/>
                        ))}
                    </datalist>
                    <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => {
                            setCompareOpen(false)
                            setSelectedId(null)
                        }}
                    >
                        {t('history.compareBack')}
                    </Button>
                </div>
            ) : (
                <form
                    onSubmit={applyFilters}
                    className="flex shrink-0 items-center gap-2 border-b border-border p-2"
                >
                    <input
                        value={draft.author ?? ''}
                        onChange={(event) => setDraft({...draft, author: event.target.value})}
                        placeholder={t('history.filterAuthor')}
                        aria-label={t('history.filterAuthor')}
                        className="w-32 rounded-md border border-border bg-elevated px-2 py-1 text-xs text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                    />
                    <input
                        value={draft.branch ?? ''}
                        onChange={(event) => setDraft({...draft, branch: event.target.value})}
                        placeholder={t('history.filterBranch')}
                        aria-label={t('history.filterBranch')}
                        className="w-32 rounded-md border border-border bg-elevated px-2 py-1 text-xs text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                    />
                    <input
                        value={draft.message ?? ''}
                        onChange={(event) => setDraft({...draft, message: event.target.value})}
                        placeholder={t('history.filterMessage')}
                        aria-label={t('history.filterMessage')}
                        className="min-w-0 flex-1 rounded-md border border-border bg-elevated px-2 py-1 text-xs text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                    />
                    <Button type="submit" size="sm" variant="primary">
                        {t('history.search')}
                    </Button>
                    {hasDraft || filters.author || filters.branch || filters.message ? (
                        <Button size="sm" variant="ghost" onClick={clearFilters}>
                            {t('history.clear')}
                        </Button>
                    ) : null}
                    <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        onClick={() => setCompareOpen(true)}
                    >
                        {t('history.compare')}
                    </Button>
                </form>
            )}

            <div className="flex min-h-0 flex-1">
                <div className="min-h-0 flex-1 overflow-y-auto p-2">
                    {(compareOpen ? compare.isPending && compare.fetchStatus !== 'idle' : history.isPending) ? (
                        <div className="flex items-center gap-2 p-2 text-sm text-muted">
                            <Spinner/>
                            {t(compareOpen ? 'history.compareLoading' : 'history.loading')}
                        </div>
                    ) : compareOpen && compare.isError ? (
                        <p className="p-2 text-sm text-vcs-deleted">
                            {t('history.compareFailed')}: {t(apiErrorKey(compare.error))}
                        </p>
                    ) : !compareOpen && history.isError ? (
                        <p className="p-2 text-sm text-vcs-deleted">
                            {t('history.loadFailed')}: {t(apiErrorKey(history.error))}
                        </p>
                    ) : revisions.length === 0 ? (
                        <EmptyState
                            title={t(compareOpen ? 'history.compareEmptyTitle' : 'history.emptyTitle')}
                            description={t(compareOpen ? 'history.compareEmptyDescription' : 'history.emptyDescription')}
                        />
                    ) : (
                        <>
                            <ul className="flex flex-col" role="listbox" aria-label={t('history.listLabel')}>
                                {revisions.map((revision, index) => (
                                    <CommitRow
                                        key={revision.id}
                                        revision={revision}
                                        formatDate={formatDate}
                                        graph={graph.rows[index]}
                                        laneCount={graph.laneCount}
                                        selected={revision.id === selectedId}
                                        onSelect={() => setSelectedId(revision.id)}
                                        onOpenMenu={(position, target) =>
                                            openCommitMenu(revision.id, position, target)
                                        }
                                    />
                                ))}
                            </ul>
                            {cherryPick.isError ? (
                                <p className="px-2 py-1 text-xs text-vcs-deleted">
                                    {t('cherryPick.failed')}: {t(apiErrorKey(cherryPick.error))}
                                </p>
                            ) : null}
                            {revert.isError ? (
                                <p className="px-2 py-1 text-xs text-vcs-deleted">
                                    {t('revert.failed')}: {t(apiErrorKey(revert.error))}
                                </p>
                            ) : null}
                            {!compareOpen && history.hasNextPage ? (
                                <div className="flex justify-center p-2">
                                    <Button
                                        size="sm"
                                        disabled={history.isFetchingNextPage}
                                        onClick={() => history.fetchNextPage()}
                                    >
                                        {history.isFetchingNextPage
                                            ? t('history.loadingMore')
                                            : t('history.loadMore')}
                                    </Button>
                                </div>
                            ) : null}
                        </>
                    )}
                </div>

                <aside className="hidden w-96 shrink-0 border-l border-border md:block">
                    {selected ? (
                        <CommitDetail
                            repositoryId={repositoryId}
                            revision={selected}
                            formatDate={formatDate}
                        />
                    ) : (
                        <p className="p-4 text-xs text-muted">{t('commit.detailEmpty')}</p>
                    )}
                </aside>
            </div>

            {commitMenu != null ? (
                <>
                    {/* A full-screen catcher closes the menu on any outside click. */}
                    <div
                        className="fixed inset-0 z-40"
                        onClick={() => closeCommitMenu()}
                        onContextMenu={(event) => {
                            event.preventDefault()
                            closeCommitMenu()
                        }}
                    />
                    <div
                        role="menu"
                        style={{top: commitMenu.y, left: commitMenu.x}}
                        className="fixed z-50 min-w-max rounded-md border border-border bg-elevated py-1 text-[13px] shadow-lg"
                    >
                        <button
                            ref={commitMenuItemRef}
                            type="button"
                            role="menuitem"
                            disabled={cherryPick.isPending}
                            onClick={runCherryPick}
                            className="block w-full px-3 py-1 text-left text-primary/90 outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('cherryPick.action')}
                        </button>
                        <button
                            type="button"
                            role="menuitem"
                            disabled={revert.isPending}
                            onClick={runRevert}
                            className="block w-full px-3 py-1 text-left text-primary/90 outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('revert.action')}
                        </button>
                    </div>
                </>
            ) : null}
        </div>
    )
}
