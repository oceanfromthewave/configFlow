import type {ReactNode} from 'react'

import {
    useAbortRebase,
    useContinueRebase,
    useSkipRebase,
    useStageFiles,
    useUnstageFiles,
    useWorkingTreeStatus,
} from '@/entities/repository/api/repositories'
import type {
    ChangeType,
    ConflictedFile,
    FileChange,
    Resolution,
} from '@/entities/repository/model/types'
import {useT} from '@/shared/i18n'
import type {MessageKey} from '@/shared/i18n'
import {apiErrorKey} from '@/shared/lib/apiErrorMessage'
import {cn} from '@/shared/lib/cn'
import {useUiStore} from '@/shared/lib/uiStore'
import {Badge, Button, EmptyState, IconButton, Spinner} from '@/shared/ui'
import type {BadgeVariant} from '@/shared/ui'

/** Single-letter marker + colour per change type (docs/06 §3 tokens). */
const CHANGE_STYLE: Record<ChangeType, { letter: string; variant: BadgeVariant }> = {
    ADDED: {letter: 'A', variant: 'added'},
    MODIFIED: {letter: 'M', variant: 'modified'},
    DELETED: {letter: 'D', variant: 'deleted'},
    RENAMED: {letter: 'R', variant: 'renamed'},
    COPIED: {letter: 'C', variant: 'renamed'},
    CONFLICTED: {letter: '!', variant: 'conflicted'},
    UNTRACKED: {letter: '?', variant: 'default'},
    IGNORED: {letter: 'I', variant: 'default'},
    LOCKED_BY_OTHER: {letter: 'L', variant: 'default'},
}

/** Conflict resolution state, translated instead of shown as a raw enum. */
const RESOLUTION_KEY: Record<Resolution, MessageKey> = {
    UNRESOLVED: 'workingTree.resolutionUnresolved',
    MINE: 'workingTree.resolutionMine',
    THEIRS: 'workingTree.resolutionTheirs',
    MANUAL: 'workingTree.resolutionManual',
}

interface RowAction {
    label: string
    symbol: string
    disabled: boolean
    onClick: () => void
}

function FileRow({
                     path,
                     letter,
                     variant,
                     hint,
                     action,
                     selected,
                     onSelect,
                 }: {
    path: string
    letter: string
    variant: BadgeVariant
    hint?: string
    action: RowAction
    selected: boolean
    onSelect: () => void
}) {
    return (
        <li
            className={cn(
                'group flex items-center gap-2 rounded pr-2 hover:bg-elevated',
                selected && 'bg-elevated',
            )}
        >
            {/* The row itself selects; the action button sits outside it so a click
                on '+' does not also change what the diff panel shows. */}
            <button
                type="button"
                onClick={onSelect}
                aria-current={selected}
                className="flex min-w-0 flex-1 items-center gap-2 rounded px-2 py-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-accent/60"
            >
                <Badge variant={variant} className="w-5 shrink-0 justify-center">
                    {letter}
                </Badge>
                <span className="min-w-0 flex-1 truncate font-mono text-xs text-primary" title={path}>
                    {path}
                </span>
                {hint ? <span className="shrink-0 text-[11px] text-muted">{hint}</span> : null}
            </button>
            <IconButton
                size="sm"
                aria-label={`${action.label}: ${path}`}
                title={action.label}
                disabled={action.disabled}
                onClick={action.onClick}
                className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
            >
                {action.symbol}
            </IconButton>
        </li>
    )
}

function Section({
                     title,
                     count,
                     action,
                     children,
                 }: {
    title: string
    count: number
    action: { label: string; disabled: boolean; onClick: () => void }
    children: ReactNode
}) {
    if (count === 0) return null
    return (
        <section className="flex flex-col gap-1">
            <div className="flex items-center justify-between gap-2 px-2">
                <h3 className="select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
                    {title} ({count})
                </h3>
                <Button
                    variant="ghost"
                    size="sm"
                    disabled={action.disabled}
                    onClick={action.onClick}
                >
                    {action.label}
                </Button>
            </div>
            <ul className="flex flex-col">{children}</ul>
        </section>
    )
}

/** Working-tree tab (docs/06 §2): staged / unstaged / conflicted file lists. */
export function WorkingTreePanel() {
    const t = useT()
    const repositoryId = useUiStore((s) => s.currentRepositoryId)
    const selectedFile = useUiStore((s) => s.selectedFile)
    const selectFile = useUiStore((s) => s.selectFile)
    const status = useWorkingTreeStatus(repositoryId)
    const stageFiles = useStageFiles()
    const unstageFiles = useUnstageFiles()
    const continueRebase = useContinueRebase()
    const abortRebase = useAbortRebase()
    const skipRebase = useSkipRebase()

    // The query is disabled without a repository, so it would stay `isPending`
    // forever and render a spinner that never resolves.
    if (repositoryId == null) {
        return (
            <EmptyState
                title={t('center.emptyTitle')}
                description={t('center.emptyDescription')}
            />
        )
    }

    if (status.isPending) {
        return (
            <div className="flex items-center gap-2 p-4 text-sm text-muted">
                <Spinner/>
                {t('workingTree.loading')}
            </div>
        )
    }

    if (status.isError) {
        return (
            <p className="p-4 text-sm text-vcs-deleted">{t('workingTree.loadFailed')}</p>
        )
    }

    const {staged, unstaged, conflicted, rebasing} = status.data
    const total = staged.length + unstaged.length + conflicted.length

    // A rebase stopped with nothing left to show (already staged and ready for
    // continue) still needs the banner, so the clean state only applies without one.
    if (total === 0 && !rebasing) {
        return (
            <EmptyState
                title={t('workingTree.cleanTitle')}
                description={t('workingTree.cleanDescription')}
            />
        )
    }

    const rebaseBusy = continueRebase.isPending || abortRebase.isPending || skipRebase.isPending
    const rebaseFailure = continueRebase.error ?? abortRebase.error ?? skipRebase.error

    // One request may still be in flight; a second click would race it.
    const busy = stageFiles.isPending || unstageFiles.isPending
    const failure = stageFiles.error ?? unstageFiles.error

    // Starting one action clears the other's banner: `failure` merges both, so a
    // stale error would otherwise outlive the operation that produced it.
    const stage = (paths: string[]) => {
        unstageFiles.reset()
        stageFiles.mutate({repositoryId, paths})
    }
    const unstage = (paths: string[]) => {
        stageFiles.reset()
        unstageFiles.mutate({repositoryId, paths})
    }

    const stageAction = (path: string): RowAction => ({
        label: t('workingTree.stageFile'),
        symbol: '+',
        disabled: busy,
        onClick: () => stage([path]),
    })

    const isSelected = (path: string, staged: boolean) =>
        selectedFile?.kind === 'working' &&
        selectedFile.path === path &&
        selectedFile.staged === staged

    const renderStaged = (change: FileChange) => {
        const style = CHANGE_STYLE[change.type]
        return (
            <FileRow
                key={change.path}
                path={change.path}
                letter={style.letter}
                variant={style.variant}
                hint={change.oldPath ? `← ${change.oldPath}` : undefined}
                selected={isSelected(change.path, true)}
                onSelect={() => selectFile({kind: 'working', path: change.path, staged: true})}
                action={{
                    label: t('workingTree.unstageFile'),
                    symbol: '−',
                    disabled: busy,
                    onClick: () => unstage([change.path]),
                }}
            />
        )
    }

    const renderUnstaged = (change: FileChange) => {
        const style = CHANGE_STYLE[change.type]
        return (
            <FileRow
                key={change.path}
                path={change.path}
                letter={style.letter}
                variant={style.variant}
                hint={change.oldPath ? `← ${change.oldPath}` : undefined}
                selected={isSelected(change.path, false)}
                onSelect={() => selectFile({kind: 'working', path: change.path, staged: false})}
                action={stageAction(change.path)}
            />
        )
    }

    const renderConflict = (file: ConflictedFile) => (
        <FileRow
            key={file.path}
            path={file.path}
            letter="!"
            variant="conflicted"
            hint={t(RESOLUTION_KEY[file.resolution])}
            selected={isSelected(file.path, false)}
            onSelect={() => selectFile({kind: 'working', path: file.path, staged: false})}
            action={stageAction(file.path)}
        />
    )

    return (
        <div className="flex h-full flex-col gap-3 overflow-y-auto p-2">
            {rebasing ? (
                <div className="flex flex-col gap-2 rounded border border-vcs-conflicted/40 bg-vcs-conflicted/10 p-2">
                    <p className="text-xs font-medium text-vcs-conflicted">{t('rebase.inProgress')}</p>
                    {rebaseFailure ? (
                        <p className="text-xs text-vcs-deleted">
                            {t('rebase.failed')}: {t(apiErrorKey(rebaseFailure))}
                        </p>
                    ) : null}
                    <div className="flex gap-1.5">
                        <Button
                            size="sm"
                            disabled={rebaseBusy}
                            onClick={() => continueRebase.mutate({repositoryId})}
                        >
                            {t('rebase.continue')}
                        </Button>
                        <Button
                            size="sm"
                            variant="ghost"
                            disabled={rebaseBusy}
                            onClick={() => {
                                if (window.confirm(t('rebase.skipConfirm'))) skipRebase.mutate({repositoryId})
                            }}
                        >
                            {t('rebase.skip')}
                        </Button>
                        <Button
                            size="sm"
                            variant="ghost"
                            disabled={rebaseBusy}
                            onClick={() => {
                                if (window.confirm(t('rebase.abortConfirm'))) abortRebase.mutate({repositoryId})
                            }}
                        >
                            {t('rebase.abort')}
                        </Button>
                    </div>
                </div>
            ) : null}

            {failure ? (
                <p className="px-2 text-xs text-vcs-deleted">
                    {t('workingTree.actionFailed')}: {t(apiErrorKey(failure))}
                </p>
            ) : null}

            <Section
                title={t('workingTree.conflicted')}
                count={conflicted.length}
                action={{
                    label: t('workingTree.stageAll'),
                    disabled: busy,
                    onClick: () => stage(conflicted.map((file) => file.path)),
                }}
            >
                {conflicted.map(renderConflict)}
            </Section>

            <Section
                title={t('workingTree.staged')}
                count={staged.length}
                action={{
                    label: t('workingTree.unstageAll'),
                    disabled: busy,
                    onClick: () => unstage(staged.map((change) => change.path)),
                }}
            >
                {staged.map(renderStaged)}
            </Section>

            <Section
                title={t('workingTree.unstaged')}
                count={unstaged.length}
                action={{
                    label: t('workingTree.stageAll'),
                    disabled: busy,
                    onClick: () => stage(unstaged.map((change) => change.path)),
                }}
            >
                {unstaged.map(renderUnstaged)}
            </Section>
        </div>
    )
}