import { useState } from 'react'
import type { FormEvent } from 'react'

import {
  useCredentials,
  useDeleteCredential,
  useSaveCredential,
} from '@/entities/credential/api/credentials'
import {
  AI_KEY_HOST,
  AI_KEY_PROTOCOL,
  AI_KEY_USERNAME,
  isAiKeyCredential,
} from '@/entities/credential/model/aiKeyTarget'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { Button, Spinner } from '@/shared/ui'

const inputClass =
  'rounded-md border border-border bg-base px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60'

/**
 * Claude API key for commit-message generation. Rides the same credential store as
 * remote auth (docs/07 §125), at a fixed host/protocol/username, so this panel only
 * ever shows whether that one row exists — never the secret itself.
 */
export function AiKeyPanel() {
  const t = useT()
  const credentials = useCredentials()
  const save = useSaveCredential()
  const del = useDeleteCredential()
  const [key, setKey] = useState('')

  const existing = credentials.data?.find(isAiKeyCredential)
  // Save and delete hit the same credential row; letting both run at once races.
  const busy = save.isPending || del.isPending
  const canSubmit = key.trim() !== '' && !busy

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    save.mutate(
      { host: AI_KEY_HOST, protocol: AI_KEY_PROTOCOL, username: AI_KEY_USERNAME, secret: key.trim() },
      { onSuccess: () => setKey('') },
    )
  }

  return (
    <section className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-primary">{t('settings.aiKey.title')}</h2>
        <p className="mt-1 text-xs text-muted">{t('settings.aiKey.description')}</p>
      </div>

      {credentials.isPending ? (
        <p className="flex items-center gap-2 text-xs text-muted">
          <Spinner />
          {t('settings.aiKey.loading')}
        </p>
      ) : credentials.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('settings.aiKey.loadFailed')}: {t(apiErrorKey(credentials.error))}
        </p>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-3 rounded-lg border border-border bg-elevated p-4">
          <p className="text-xs text-muted">
            {existing ? t('settings.aiKey.configured') : t('settings.aiKey.notConfigured')}
          </p>

          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted">{t('settings.aiKey.key')}</span>
            <input
              type="password"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder={t('settings.aiKey.keyPlaceholder')}
              className={inputClass}
            />
          </label>

          {save.isError ? (
            <p className="text-xs text-vcs-deleted">
              {t('settings.aiKey.saveFailed')}: {t(apiErrorKey(save.error))}
            </p>
          ) : null}
          {del.isError ? (
            <p className="text-xs text-vcs-deleted">
              {t('settings.aiKey.removeFailed')}: {t(apiErrorKey(del.error))}
            </p>
          ) : null}

          <div className="flex gap-2">
            <Button type="submit" disabled={!canSubmit}>
              {save.isPending ? t('settings.aiKey.saving') : t('settings.aiKey.save')}
            </Button>
            {existing ? (
              <Button
                type="button"
                variant="ghost"
                disabled={busy}
                onClick={() => del.mutate(existing.id)}
              >
                {del.isPending ? t('settings.aiKey.removing') : t('settings.aiKey.remove')}
              </Button>
            ) : null}
          </div>
        </form>
      )}
    </section>
  )
}
