import {useState, type FormEvent, type KeyboardEvent} from 'react'

import {
    useCommit,
    useGenerateCommitMessage,
    useWorkingTreeStatus,
} from '@/entities/repository/api/repositories'
import {useT} from '@/shared/i18n'
import {apiErrorKey} from '@/shared/lib/apiErrorMessage'
import {useUiStore} from '@/shared/lib/uiStore'
import {Button} from '@/shared/ui'

/** Commit composer (docs/06 §2): message + amend, commits whatever is staged. */
export function CommitBox() {
    const t = useT()
    const repositoryId = useUiStore((s) => s.currentRepositoryId)
    const status = useWorkingTreeStatus(repositoryId)
    const commit = useCommit()
    const generate = useGenerateCommitMessage()

    const [message, setMessage] = useState('')
    const [amend, setAmend] = useState(false)

    if (repositoryId == null) return null

    const stagedCount = status.data?.staged.length ?? 0
    // Git refuses to commit while any path is unmerged, so block it here rather
    // than letting the request fail on the server.
    const blockedByConflicts = (status.data?.conflicted.length ?? 0) > 0
    // Amending rewrites the previous commit, so it is meaningful with an empty index.
    const hasSomethingToCommit = stagedCount > 0 || amend
    const canSubmit =
        message.trim().length > 0 &&
        hasSomethingToCommit &&
        !blockedByConflicts &&
        !commit.isPending

    function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        if (!canSubmit) return
        commit.mutate(
            {repositoryId: repositoryId!, message: message.trim(), amend},
            {
                onSuccess: () => {
                    setMessage('')
                    setAmend(false)
                },
            },
        )
    }

    // The provider only sees staged changes, so there is nothing to describe
    // until something is staged.
    const canGenerate = stagedCount > 0 && !generate.isPending

    function generateMessage() {
        if (!canGenerate) return
        generate.mutate(repositoryId!, {
            onSuccess: (result) => setMessage(result.message),
        })
    }

    function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
        // Enter inserts a newline; Ctrl/Cmd+Enter is the commit shortcut.
        if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.currentTarget.form?.requestSubmit()
        }
    }

    return (
        <form
            onSubmit={submit}
            className="flex shrink-0 flex-col gap-2 border-t border-border p-2"
        >
            <textarea
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                onKeyDown={handleKeyDown}
                rows={3}
                placeholder={t('commit.messagePlaceholder')}
                aria-label={t('commit.messagePlaceholder')}
                className="resize-none rounded-md border border-border bg-elevated px-3 py-2 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
            />

            <div className="flex items-center justify-between gap-2">
                <label className="flex select-none items-center gap-1.5 text-xs text-muted">
                    <input
                        type="checkbox"
                        checked={amend}
                        onChange={(event) => setAmend(event.target.checked)}
                        className="accent-accent"
                    />
                    {t('commit.amend')}
                </label>

                <div className="flex items-center gap-2">
                    <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        onClick={generateMessage}
                        disabled={!canGenerate}
                    >
                        {generate.isPending
                            ? t('commit.generating')
                            : t('commit.generate')}
                    </Button>

                    <Button type="submit" variant="primary" size="sm" disabled={!canSubmit}>
                        {commit.isPending ? t('commit.committing') : t('commit.submit')}
                    </Button>
                </div>
            </div>

            {blockedByConflicts ? (
                <p className="text-xs text-vcs-conflicted">
                    {t('commit.blockedByConflicts')}
                </p>
            ) : !hasSomethingToCommit ? (
                <p className="text-xs text-muted">{t('commit.nothingStaged')}</p>
            ) : null}

            {generate.isError ? (
                <p className="text-xs text-vcs-deleted">
                    {t('commit.generateFailed')}: {t(apiErrorKey(generate.error))}
                </p>
            ) : null}

            {commit.isError ? (
                <p className="text-xs text-vcs-deleted">
                    {t('commit.failed')}: {t(apiErrorKey(commit.error))}
                </p>
            ) : null}

            {commit.isSuccess ? (
                <p className="text-xs text-vcs-added">
                    {t('commit.created', {
                        revision: commit.data.revisionId.slice(0, 7),
                    })}
                </p>
            ) : null}
        </form>
    )
}