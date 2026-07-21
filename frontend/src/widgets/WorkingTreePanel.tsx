import type {
    ChangeType,
    ConflictedFile,
    FileChange,
} from '@/shared/api/repositories'
import {useWorkingTreeStatus} from '@/shared/api/repositories'
import {useT} from '@/shared/i18n'
import {useUiStore} from '@/shared/lib/uiStore'
import {Badge, EmptyState, Spinner} from '@/shared/ui'
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

function FileRow({
                     path,
                     letter,
                     variant,
                     hint,
                 }: {
    path: string
    letter: string
    variant: BadgeVariant
    hint?: string
}) {
    return (
        <li className="flex items-center gap-2 rounded px-2 py-1 hover:bg-elevated">
            <Badge variant={variant} className="w-5 justify-center">
                {letter}
            </Badge>
            <span className="truncate font-mono text-xs text-primary" title={path}>
          {path}
        </span>
            {hint ? <span className="text-[11px] text-muted">{hint}</span> : null}
        </li>
    )
}

function Section({
                     title,
                     count,
                     children,
                 }: {
    title: string
    count: number
    children: React.ReactNode
}) {
    if (count === 0) return null
    return (
        <section className="flex flex-col gap-1">
            <h3 className="select-none px-2 text-[11px] font-semibold uppercase tracking-wider text-muted">
                {title} ({count})
            </h3>
            <ul className="flex flex-col">{children}</ul>
        </section>
    )
}

/** Working-tree tab (docs/06 §2): staged / unstaged / conflicted file lists. */
export function WorkingTreePanel() {
    const t = useT()
    const repositoryId = useUiStore((s) => s.currentRepositoryId)
    const status = useWorkingTreeStatus(repositoryId)

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

    const {staged, unstaged, conflicted} = status.data
    const total = staged.length + unstaged.length + conflicted.length

    if (total === 0) {
        return (
            <EmptyState
                title={t('workingTree.cleanTitle')}
                description={t('workingTree.cleanDescription')}
            />
        )
    }

    const renderChange = (change: FileChange) => {
        const style = CHANGE_STYLE[change.type]
        return (
            <FileRow
                key={change.path}
                path={change.path}
                letter={style.letter}
                variant={style.variant}
                hint={change.oldPath ? `← ${change.oldPath}` : undefined}
            />
        )
    }

    const renderConflict = (file: ConflictedFile) => (
        <FileRow
            key={file.path}
            path={file.path}
            letter="!"
            variant="conflicted"
            hint={file.resolution}
        />
    )

    return (
        <div className="flex h-full flex-col gap-3 overflow-y-auto p-2">
            <Section title={t('workingTree.conflicted')} count={conflicted.length}>
                {conflicted.map(renderConflict)}
            </Section>
            <Section title={t('workingTree.staged')} count={staged.length}>
                {staged.map(renderChange)}
            </Section>
            <Section title={t('workingTree.unstaged')} count={unstaged.length}>
                {unstaged.map(renderChange)}
            </Section>
        </div>
    )
}
