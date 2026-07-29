import {useCommitChanges} from '@/entities/repository/api/repositories'
import type {ChangeType, Revision} from '@/entities/repository/model/types'
import {useT} from '@/shared/i18n'
import {apiErrorKey} from '@/shared/lib/apiErrorMessage'
import {useUiStore} from '@/shared/lib/uiStore'
import {Badge, Spinner} from '@/shared/ui'
import type {BadgeVariant} from '@/shared/ui'

/** Single-letter marker + colour per change type, as in the working-tree panel. */
const CHANGE_STYLE: Record<ChangeType, {letter: string; variant: BadgeVariant}> = {
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

/** The last path segment reads as the file name; the rest is its directory. */
function baseName(path: string) {
    const segments = path.split('/')
    return segments[segments.length - 1] || path
}

/**
 * Details of the commit selected in the history list: its metadata and the
 * files it changed against its first parent. The changed-file list is fetched
 * on demand; the metadata is already in the selected revision.
 */
export function CommitDetail({
                                repositoryId,
                                revision,
                                formatDate,
                            }: {
    repositoryId: string
    revision: Revision
    formatDate: (iso: string) => string
}) {
    const t = useT()
    const selectFile = useUiStore((s) => s.selectFile)
    const selectedFile = useUiStore((s) => s.selectedFile)
    const changes = useCommitChanges(repositoryId, revision.id)

    const isSelected = (path: string) =>
        selectedFile?.kind === 'commit' &&
        selectedFile.revision === revision.id &&
        selectedFile.path === path

    return (
        <div className="flex h-full flex-col overflow-y-auto p-3 text-xs">
            <p className="whitespace-pre-wrap break-words text-sm text-primary">
                {revision.message}
            </p>

            <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-muted">
                <dt className="text-right">{revision.author.name}</dt>
                <dd className="truncate" title={revision.author.email ?? undefined}>
                    {revision.author.email}
                </dd>
                <dt className="text-right font-mono">{revision.id.slice(0, 10)}</dt>
                <dd>
                    <time dateTime={revision.timestamp}>{formatDate(revision.timestamp)}</time>
                </dd>
                {revision.parents.length > 0 ? (
                    <>
                        <dt className="text-right">{t('commit.detailParents')}</dt>
                        <dd className="font-mono">
                            {revision.parents.map((parent) => parent.slice(0, 10)).join(', ')}
                        </dd>
                    </>
                ) : null}
            </dl>

            <h3 className="mt-4 mb-1 shrink-0 font-medium text-muted">
                {t('commit.detailChangedFiles')}
            </h3>

            {changes.isPending ? (
                <div className="flex items-center gap-2 py-1 text-muted">
                    <Spinner/>
                    {t('commit.changesLoading')}
                </div>
            ) : changes.isError ? (
                <p className="py-1 text-vcs-deleted">
                    {t('commit.changesLoadFailed')}: {t(apiErrorKey(changes.error))}
                </p>
            ) : changes.data.length === 0 ? (
                <p className="py-1 text-muted">{t('commit.changesEmpty')}</p>
            ) : (
                <ul className="flex flex-col" role="listbox" aria-label={t('commit.detailChangedFiles')}>
                    {changes.data.map((change) => {
                        const style = CHANGE_STYLE[change.type]
                        const selected = isSelected(change.path)
                        const select = () =>
                            selectFile({kind: 'commit', path: change.path, revision: revision.id})
                        return (
                            <li
                                key={change.path}
                                role="option"
                                tabIndex={0}
                                aria-selected={selected}
                                className={`flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 outline-none focus-visible:ring-2 focus-visible:ring-accent/60 ${
                                    selected ? 'bg-elevated ring-1 ring-accent/50' : 'hover:bg-elevated'
                                }`}
                                onClick={select}
                                onKeyDown={(event) => {
                                    if (event.key === 'Enter' || event.key === ' ') {
                                        event.preventDefault()
                                        select()
                                    }
                                }}
                            >
                                <Badge variant={style.variant}>{style.letter}</Badge>
                                <span className="min-w-0 flex-1 truncate text-primary" title={change.path}>
                                    {baseName(change.path)}
                                    {change.oldPath ? (
                                        <span className="ml-1 text-muted">← {change.oldPath}</span>
                                    ) : null}
                                </span>
                            </li>
                        )
                    })}
                </ul>
            )}
        </div>
    )
}
