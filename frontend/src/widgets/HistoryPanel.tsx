import {useMemo, useState, type FormEvent} from 'react'

import {useHistory} from '@/entities/repository/api/repositories'
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
function GraphColumn({row, laneCount}: {row?: RowGraph; laneCount: number}) {
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

function RefLabels({labels}: {labels: RefLabel[]}) {
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
                   }: {
    revision: Revision
    formatDate: (iso: string) => string
    graph?: RowGraph
    laneCount: number
    selected: boolean
    onSelect: () => void
}) {
    return (
        <li
            className={`flex cursor-pointer items-center gap-2 rounded px-2 ${
                selected ? 'bg-elevated ring-1 ring-accent/50' : 'hover:bg-elevated'
            }`}
            style={{height: ROW_HEIGHT}}
            aria-current={selected}
            onClick={onSelect}
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

    // Draft vs applied: typing must not refire the query on every keystroke.
    const [draft, setDraft] = useState<HistoryFilters>({author: '', message: ''})
    const [filters, setFilters] = useState<HistoryFilters>({})
    const [selectedId, setSelectedId] = useState<string | null>(null)

    const history = useHistory(repositoryId, filters)

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
        const items = history.data?.pages.flatMap((page) => page.items) ?? []
        return {revisions: items, graph: computeCommitGraph(items)}
    }, [history.data])

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
        setFilters({author: draft.author, message: draft.message})
    }

    function clearFilters() {
        setDraft({author: '', message: ''})
        setFilters({})
    }

    const hasDraft = Boolean(draft.author?.trim() || draft.message?.trim())
    // The selection is held by id; if that commit is no longer in the loaded set
    // (a filter narrowed it away), the detail pane falls back to its empty hint.
    const selected = revisions.find((revision) => revision.id === selectedId) ?? null

    return (
        <div className="flex h-full flex-col">
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
                    value={draft.message ?? ''}
                    onChange={(event) => setDraft({...draft, message: event.target.value})}
                    placeholder={t('history.filterMessage')}
                    aria-label={t('history.filterMessage')}
                    className="min-w-0 flex-1 rounded-md border border-border bg-elevated px-2 py-1 text-xs text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                />
                <Button type="submit" size="sm" variant="primary">
                    {t('history.search')}
                </Button>
                {hasDraft || filters.author || filters.message ? (
                    <Button size="sm" variant="ghost" onClick={clearFilters}>
                        {t('history.clear')}
                    </Button>
                ) : null}
            </form>

            <div className="flex min-h-0 flex-1">
                <div className="min-h-0 flex-1 overflow-y-auto p-2">
                    {history.isPending ? (
                        <div className="flex items-center gap-2 p-2 text-sm text-muted">
                            <Spinner/>
                            {t('history.loading')}
                        </div>
                    ) : history.isError ? (
                        <p className="p-2 text-sm text-vcs-deleted">
                            {t('history.loadFailed')}: {t(apiErrorKey(history.error))}
                        </p>
                    ) : revisions.length === 0 ? (
                        <EmptyState
                            title={t('history.emptyTitle')}
                            description={t('history.emptyDescription')}
                        />
                    ) : (
                        <>
                            <ul className="flex flex-col">
                                {revisions.map((revision, index) => (
                                    <CommitRow
                                        key={revision.id}
                                        revision={revision}
                                        formatDate={formatDate}
                                        graph={graph.rows[index]}
                                        laneCount={graph.laneCount}
                                        selected={revision.id === selectedId}
                                        onSelect={() => setSelectedId(revision.id)}
                                    />
                                ))}
                            </ul>
                            {history.hasNextPage ? (
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
        </div>
    )
}
