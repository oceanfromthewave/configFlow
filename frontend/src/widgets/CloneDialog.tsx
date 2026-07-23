import { useState, type FormEvent } from 'react'

import {
  repositoryNameFromUrl,
  useClone,
} from '@/entities/repository/api/repositories'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { canPickDirectory, pickDirectory } from '@/shared/lib/nativeBridge'
import { Button } from '@/shared/ui'

/** Joins a parent directory and a name without caring which separator is in use. */
function joinPath(parent: string, name: string): string {
  const separator = parent.includes('\\') ? '\\' : '/'
  return `${parent.replace(/[\\/]+$/, '')}${separator}${name}`
}

/**
 * Clone form for the Welcome screen.
 *
 * The user picks a parent folder and the target is derived from the URL, the way
 * `git clone` does. The derived path is shown rather than assumed, because that
 * is the one thing about a clone that is easy to get wrong and expensive to
 * undo.
 */
export function CloneDialog({ onClose }: { onClose: () => void }) {
  const t = useT()
  const clone = useClone()

  const [url, setUrl] = useState('')
  const [parent, setParent] = useState('')

  const derivedName = repositoryNameFromUrl(url)
  const target =
    parent.trim() !== '' && derivedName != null
      ? joinPath(parent.trim(), derivedName)
      : null
  const canSubmit = target != null && !clone.isPending

  async function browse() {
    const picked = await pickDirectory()
    if (picked != null) setParent(picked)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (target == null) return
    clone.mutate(
      { url: url.trim(), localPath: target },
      // The clone itself runs on the queue; closing now returns the user to the
      // list, which fills in when `repository.registered` arrives.
      { onSuccess: onClose },
    )
  }

  return (
    <form
      onSubmit={submit}
      className="flex flex-col gap-3 rounded-lg border border-border bg-elevated p-4"
    >
      <h2 className="text-sm font-medium text-primary">{t('clone.title')}</h2>

      <label className="flex flex-col gap-1">
        <span className="text-xs text-muted">{t('clone.url')}</span>
        <input
          autoFocus
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder={t('clone.urlPlaceholder')}
          className="rounded-md border border-border bg-base px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-xs text-muted">{t('clone.parent')}</span>
        <div className="flex gap-2">
          <input
            value={parent}
            onChange={(event) => setParent(event.target.value)}
            placeholder={t('clone.parentPlaceholder')}
            className="min-w-0 flex-1 rounded-md border border-border bg-base px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
          />
          {canPickDirectory() ? (
            <Button type="button" onClick={browse}>
              {t('clone.browse')}
            </Button>
          ) : null}
        </div>
      </label>

      {target != null ? (
        <p className="font-mono text-xs text-muted">
          {t('clone.target')}: {target}
        </p>
      ) : (
        <p className="text-xs text-muted">{t('clone.needParent')}</p>
      )}

      {clone.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('clone.failed')}: {t(apiErrorKey(clone.error))}
        </p>
      ) : null}

      <div className="flex gap-2">
        <Button type="submit" variant="primary" disabled={!canSubmit}>
          {clone.isPending ? t('clone.cloning') : t('clone.submit')}
        </Button>
        <Button type="button" variant="ghost" onClick={onClose}>
          {t('clone.cancel')}
        </Button>
      </div>
    </form>
  )
}
