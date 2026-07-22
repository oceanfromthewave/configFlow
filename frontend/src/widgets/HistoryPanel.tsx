import {useMemo, useState, type FormEvent} from 'react'

import {useHistory} from '@/entities/repository/api/repositories'
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
                   }: {
    revision: Revision
    formatDate: (iso: string) => string
}) {
    return (
        <li className="flex items-center gap-2 rounded px-2 py-1.5 hover:bg-elevated">
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

    const history = useHistory(repositoryId, filters)

    const formatDate = useMemo(() => {
        const format = new Intl.DateTimeFormat(locale, {
            dateStyle: 'medium',
            timeStyle: 'short',
        })
        return (iso: string) => format.format(new Date(iso))
    }, [locale])

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
    const revisions = history.data?.pages.flatMap((page) => page.items) ?? []

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
                            {revisions.map((revision) => (
                                <CommitRow
                                    key={revision.id}
                                    revision={revision}
                                    formatDate={formatDate}
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
        </div>
    )
}
