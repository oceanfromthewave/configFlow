import {useState} from 'react'

import {useCommitFileDiff, useFileDiff} from '@/entities/repository/api/repositories'
import type {DiffHunk} from '@/entities/repository/model/types'
import {toSideBySideRows} from '@/entities/repository/lib/sideBySideDiff'
import type {SideBySideRowKind} from '@/entities/repository/lib/sideBySideDiff'
import {useT} from '@/shared/i18n'
import {apiErrorKey} from '@/shared/lib/apiErrorMessage'
import {cn} from '@/shared/lib/cn'
import {useUiStore} from '@/shared/lib/uiStore'
import {Badge, Button, EmptyState, Spinner} from '@/shared/ui'

type LineKind = 'context' | 'added' | 'removed'

/** The unified-diff prefix is what decides how a row reads. */
function kindOf(line: string): LineKind {
    if (line.startsWith('+')) return 'added'
    if (line.startsWith('-')) return 'removed'
    return 'context'
}

const LINE_CLASS: Record<LineKind, string> = {
    context: 'text-muted',
    added: 'bg-vcs-added/10 text-vcs-added',
    removed: 'bg-vcs-deleted/10 text-vcs-deleted',
}

/**
 * Renders one hunk as a table of old line | new line | content.
 *
 * The two gutters count independently: an added line has no number on the old
 * side and a removed line has none on the new side, which is what makes the
 * columns line up with the file as it was and as it is.
 */
function Hunk({hunk}: {hunk: DiffHunk}) {
    let oldLine = hunk.oldStart
    let newLine = hunk.newStart

    return (
        <>
            <tr className="select-none bg-elevated">
                <td colSpan={3} className="px-2 py-0.5 font-mono text-[11px] text-muted">
                    @@ -{hunk.oldStart},{hunk.oldCount} +{hunk.newStart},{hunk.newCount} @@
                </td>
            </tr>
            {hunk.lines.map((line, index) => {
                const kind = kindOf(line)
                const oldNumber = kind === 'added' ? null : oldLine++
                const newNumber = kind === 'removed' ? null : newLine++
                return (
                    <tr key={`${hunk.newStart}:${index}`} className={LINE_CLASS[kind]}>
                        <td className="w-10 select-none px-1 text-right align-top font-mono text-[11px] opacity-60">
                            {oldNumber}
                        </td>
                        <td className="w-10 select-none px-1 text-right align-top font-mono text-[11px] opacity-60">
                            {newNumber}
                        </td>
                        <td className="whitespace-pre px-2 font-mono text-xs">{line}</td>
                    </tr>
                )
            })}
        </>
    )
}

const OLD_CLASS: Record<SideBySideRowKind, string> = {
    context: 'text-muted',
    changed: 'bg-vcs-deleted/10 text-vcs-deleted',
    empty: 'bg-elevated/40',
}

const NEW_CLASS: Record<SideBySideRowKind, string> = {
    context: 'text-muted',
    changed: 'bg-vcs-added/10 text-vcs-added',
    empty: 'bg-elevated/40',
}

/** Same hunk as {@link Hunk}, but old and new lines sit in their own columns. */
function SideBySideHunk({hunk}: {hunk: DiffHunk}) {
    const rows = toSideBySideRows(hunk)

    return (
        <>
            <tr className="select-none bg-elevated">
                <td colSpan={4} className="px-2 py-0.5 font-mono text-[11px] text-muted">
                    @@ -{hunk.oldStart},{hunk.oldCount} +{hunk.newStart},{hunk.newCount} @@
                </td>
            </tr>
            {rows.map((row, index) => (
                <tr key={`${hunk.newStart}:${index}`}>
                    <td className="w-10 select-none px-1 text-right align-top font-mono text-[11px] opacity-60">
                        {row.oldNumber}
                    </td>
                    <td className={cn(
                        'w-1/2 whitespace-pre border-r border-border px-2 font-mono text-xs',
                        OLD_CLASS[row.oldKind],
                    )}>
                        {row.oldText}
                    </td>
                    <td className="w-10 select-none px-1 text-right align-top font-mono text-[11px] opacity-60">
                        {row.newNumber}
                    </td>
                    <td className={cn('w-1/2 whitespace-pre px-2 font-mono text-xs', NEW_CLASS[row.newKind])}>
                        {row.newText}
                    </td>
                </tr>
            ))}
        </>
    )
}

/** Right panel (docs/06 §1): unified diff of the file selected in the working tree. */
export function DiffPanel() {
    const t = useT()
    const repositoryId = useUiStore((s) => s.currentRepositoryId)
    const selectedFile = useUiStore((s) => s.selectedFile)
    const [view, setView] = useState<'unified' | 'split'>('unified')

    // One panel, two sources. Both hooks run every render (hooks cannot be
    // conditional) but each is disabled unless its own selection is active, so
    // only the relevant request fires.
    const working = selectedFile?.kind === 'working' ? selectedFile : null
    const commit = selectedFile?.kind === 'commit' ? selectedFile : null
    const workingDiff = useFileDiff(repositoryId, working?.path ?? null, working?.staged ?? false)
    const commitDiff = useCommitFileDiff(
        repositoryId, commit?.revision ?? null, commit?.path ?? null)
    const diff = commit ? commitDiff : workingDiff

    if (selectedFile == null) {
        return (
            <div className="h-full bg-panel">
                <EmptyState
                    icon={<span aria-hidden>&#x00B1;</span>}
                    title={t('diff.emptyTitle')}
                    description={t('diff.emptyDescription')}
                />
            </div>
        )
    }

    const added = diff.data?.hunks.reduce(
        (total, hunk) => total + hunk.lines.filter((l) => l.startsWith('+')).length, 0) ?? 0
    const removed = diff.data?.hunks.reduce(
        (total, hunk) => total + hunk.lines.filter((l) => l.startsWith('-')).length, 0) ?? 0

    return (
        <div className="flex h-full flex-col bg-panel">
            <header className="flex shrink-0 items-center gap-2 border-b border-border px-2 py-1.5">
                <span
                    className="min-w-0 flex-1 truncate font-mono text-xs text-primary"
                    title={selectedFile.path}
                >
                    {selectedFile.path}
                </span>
                {diff.data && !diff.data.binary && diff.data.hunks.length > 0 ? (
                    <>
                        <span className="shrink-0 font-mono text-[11px] text-muted">
                            {t('diff.stats', {added, removed})}
                        </span>
                        <div className="flex shrink-0 gap-1">
                            <Button
                                size="sm"
                                variant={view === 'unified' ? 'secondary' : 'ghost'}
                                aria-pressed={view === 'unified'}
                                onClick={() => setView('unified')}
                            >
                                {t('diff.unifiedView')}
                            </Button>
                            <Button
                                size="sm"
                                variant={view === 'split' ? 'secondary' : 'ghost'}
                                aria-pressed={view === 'split'}
                                onClick={() => setView('split')}
                            >
                                {t('diff.splitView')}
                            </Button>
                        </div>
                    </>
                ) : null}
                {selectedFile.kind === 'commit' ? (
                    <Badge variant="renamed">{selectedFile.revision.slice(0, 7)}</Badge>
                ) : (
                    <Badge variant={selectedFile.staged ? 'added' : 'modified'}>
                        {selectedFile.staged ? t('diff.stagedSide') : t('diff.unstagedSide')}
                    </Badge>
                )}
            </header>

            <div className={cn('min-h-0 flex-1', diff.data ? 'overflow-auto' : 'overflow-hidden')}>
                {diff.isPending ? (
                    <div className="flex items-center gap-2 p-4 text-sm text-muted">
                        <Spinner/>
                        {t('diff.loading')}
                    </div>
                ) : diff.isError ? (
                    <p className="p-4 text-sm text-vcs-deleted">
                        {t('diff.loadFailed')}: {t(apiErrorKey(diff.error))}
                    </p>
                ) : diff.data.binary ? (
                    <EmptyState
                        title={t('diff.binaryTitle')}
                        description={t('diff.binaryDescription')}
                    />
                ) : diff.data.hunks.length === 0 ? (
                    <EmptyState
                        title={t('diff.unchangedTitle')}
                        description={t('diff.unchangedDescription')}
                    />
                ) : (
                    <table className="w-full border-collapse">
                        <tbody>
                        {view === 'unified' ? diff.data.hunks.map((hunk) => (
                            <Hunk key={`${hunk.oldStart}:${hunk.newStart}`} hunk={hunk}/>
                        )) : diff.data.hunks.map((hunk) => (
                            <SideBySideHunk key={`${hunk.oldStart}:${hunk.newStart}`} hunk={hunk}/>
                        ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    )
}
