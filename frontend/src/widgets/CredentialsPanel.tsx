import { useState } from 'react'
import type { FormEvent } from 'react'

import {
  useCredentials,
  useDeleteCredential,
  useSaveCredential,
} from '@/entities/credential/api/credentials'
import type { Credential } from '@/entities/credential/model/types'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { Button, Spinner } from '@/shared/ui'

const PROTOCOLS = ['https', 'ssh', 'svn'] as const

const inputClass =
  'rounded-md border border-border bg-base px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60'

function CredentialRow({ credential }: { credential: Credential }) {
  const t = useT()
  const del = useDeleteCredential()
  // One mutation serves every row, so scope the pending state to the row clicked.
  const deleting = del.isPending && del.variables === credential.id

  return (
    <li className="flex items-center gap-3 rounded border border-border px-3 py-2">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm text-primary">
          {`${credential.protocol}://${credential.host}`}
        </p>
        <p className="truncate text-xs text-muted">
          {credential.username || t('settings.credentials.tokenOnly')}
        </p>
      </div>
      <Button
        size="sm"
        variant="ghost"
        disabled={deleting}
        onClick={() => del.mutate(credential.id)}
      >
        {deleting ? t('settings.credentials.deleting') : t('settings.credentials.delete')}
      </Button>
    </li>
  )
}

function AddCredentialForm() {
  const t = useT()
  const save = useSaveCredential()
  const [host, setHost] = useState('')
  const [protocol, setProtocol] = useState<string>(PROTOCOLS[0])
  const [username, setUsername] = useState('')
  const [secret, setSecret] = useState('')

  const canSubmit = host.trim() !== '' && secret !== '' && !save.isPending

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    save.mutate(
      {
        host: host.trim(),
        protocol,
        username: username.trim() || undefined,
        secret,
      },
      {
        onSuccess: () => {
          // Keep the protocol — the next credential is usually for the same one —
          // and never leave the secret sitting in a controlled input.
          setHost('')
          setUsername('')
          setSecret('')
        },
      },
    )
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3 rounded-lg border border-border bg-elevated p-4">
      <div className="flex flex-wrap gap-3">
        <label className="flex min-w-40 flex-1 flex-col gap-1">
          <span className="text-xs text-muted">{t('settings.credentials.host')}</span>
          <input
            value={host}
            onChange={(e) => setHost(e.target.value)}
            placeholder="github.com"
            className={inputClass}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted">{t('settings.credentials.protocol')}</span>
          <select
            value={protocol}
            onChange={(e) => setProtocol(e.target.value)}
            className={inputClass}
          >
            {PROTOCOLS.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
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
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          className={inputClass}
        />
      </label>

      {save.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('settings.credentials.addFailed')}: {t(apiErrorKey(save.error))}
        </p>
      ) : null}

      <div>
        <Button type="submit" disabled={!canSubmit}>
          {save.isPending ? t('settings.credentials.adding') : t('settings.credentials.add')}
        </Button>
      </div>
    </form>
  )
}

/** Credential settings (docs/07 §125): what is registered, plus a form to add one. */
export function CredentialsPanel() {
  const t = useT()
  const credentials = useCredentials()

  return (
    <section className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-primary">
          {t('settings.credentials.title')}
        </h2>
        <p className="mt-1 text-xs text-muted">
          {t('settings.credentials.description')}
        </p>
      </div>

      <AddCredentialForm />

      {credentials.isPending ? (
        <p className="flex items-center gap-2 text-xs text-muted">
          <Spinner />
          {t('settings.credentials.loading')}
        </p>
      ) : credentials.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('settings.credentials.loadFailed')}: {t(apiErrorKey(credentials.error))}
        </p>
      ) : credentials.data.length === 0 ? (
        <p className="text-xs text-muted">{t('settings.credentials.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {credentials.data.map((credential) => (
            <CredentialRow key={credential.id} credential={credential} />
          ))}
        </ul>
      )}
    </section>
  )
}
