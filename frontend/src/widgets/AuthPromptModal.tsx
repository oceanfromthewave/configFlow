import { useState, type FormEvent } from 'react'

import { useSaveCredential } from '@/entities/credential/api/credentials'
import { useRetryOperation } from '@/entities/operation/api/operations'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { useUiStore, type AuthPrompt } from '@/shared/lib/uiStore'
import { Button } from '@/shared/ui'

const inputClass =
  'rounded-md border border-border bg-base px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60'

/**
 * Credential prompt raised when a queued operation fails with
 * `VCS_AUTH_REQUIRED` (docs/07 §4): save a credential for the remote that
 * rejected the request, then retry the original operation — the two steps the
 * failure asks for, in one place.
 *
 * Mounted once at the app root. The operation can fail on any screen, so the
 * prompt is driven by the UI store rather than by a screen that might be
 * closed, and it renders nothing until one is waiting.
 */
export function AuthPromptModal() {
  const prompt = useUiStore((s) => s.authPrompt)
  if (!prompt) return null
  // Re-key on the operation so a second failure opens an empty form instead of
  // inheriting whatever was half-typed for the first.
  return <AuthPromptDialog key={prompt.operationId} prompt={prompt} />
}

function AuthPromptDialog({ prompt }: { prompt: AuthPrompt }) {
  const t = useT()
  const dismiss = useUiStore((s) => s.dismissAuthPrompt)
  const save = useSaveCredential()
  const retry = useRetryOperation()

  const [username, setUsername] = useState('')
  const [secret, setSecret] = useState('')

  const busy = save.isPending || retry.isPending
  const canSubmit = secret !== '' && !busy
  const target = `${prompt.protocol}://${prompt.host}`

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    save.mutate(
      {
        host: prompt.host,
        protocol: prompt.protocol,
        username: username.trim() || undefined,
        secret,
      },
      {
        onSuccess: () => {
          // The secret lives in the OS store now; never leave a copy in the input.
          setSecret('')
          // Re-running re-resolves credentials and picks up the one just saved.
          // Only close once it is safely re-queued.
          retry.mutate(prompt.operationId, { onSuccess: dismiss })
        },
      },
    )
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <form
        onSubmit={submit}
        className="flex w-full max-w-sm flex-col gap-3 rounded-lg border border-border bg-elevated p-4 shadow-lg"
      >
        <div>
          <h2 className="text-sm font-medium text-primary">{t('authPrompt.title')}</h2>
          <p className="mt-1 text-xs text-muted">
            {t('authPrompt.description', { target })}
          </p>
        </div>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted">{t('settings.credentials.username')}</span>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder={t('settings.credentials.usernamePlaceholder')}
            className={inputClass}
          />
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted">{t('settings.credentials.secret')}</span>
          <input
            autoFocus
            type="password"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            className={inputClass}
          />
        </label>

        {save.isError ? (
          <p className="text-xs text-vcs-deleted">
            {t('authPrompt.failed')}: {t(apiErrorKey(save.error))}
          </p>
        ) : retry.isError ? (
          <p className="text-xs text-vcs-deleted">
            {t('authPrompt.failed')}: {t(apiErrorKey(retry.error))}
          </p>
        ) : null}

        <div className="flex gap-2">
          <Button type="submit" variant="primary" disabled={!canSubmit}>
            {busy ? t('authPrompt.saving') : t('authPrompt.submit')}
          </Button>
          <Button type="button" variant="ghost" onClick={dismiss} disabled={busy}>
            {t('authPrompt.cancel')}
          </Button>
        </div>
      </form>
    </div>
  )
}
