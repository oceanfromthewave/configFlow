import { useState } from 'react'
import type { FormEvent } from 'react'

import {
  useCreateSshKey,
  useCredentials,
  useDeleteCredential,
  useSaveCredential,
} from '@/entities/credential/api/credentials'
import { isAiKeyCredential } from '@/entities/credential/model/aiKeyTarget'
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
    <li className="flex flex-col gap-1 rounded border border-border px-3 py-2">
      <div className="flex items-center gap-3">
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
      </div>
      {credential.publicKey ? <PublicKeyDisplay publicKey={credential.publicKey} /> : null}
      {del.isError ? (
        // A failed delete only re-enabled the button before; say why it failed.
        <p className="text-xs text-vcs-deleted">
          {t('settings.credentials.deleteFailed')}: {t(apiErrorKey(del.error))}
        </p>
      ) : null}
    </li>
  )
}

/** A public key line with a copy button — the whole reason it's shown at all. */
function PublicKeyDisplay({ publicKey }: { publicKey: string }) {
  const t = useT()
  const [copied, setCopied] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(publicKey)
      setCopyFailed(false)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // No clipboard API (insecure context, unsupported browser): say so instead of
      // leaving the click silently do nothing.
      setCopyFailed(true)
    }
  }

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2 rounded bg-base px-2 py-1">
        <code className="min-w-0 flex-1 truncate text-xs text-muted">{publicKey}</code>
        <Button size="sm" variant="ghost" onClick={copy}>
          {copied ? t('settings.credentials.sshCopied') : t('settings.credentials.sshCopy')}
        </Button>
      </div>
      {copyFailed ? (
        <p className="text-xs text-vcs-deleted">{t('settings.credentials.sshCopyFailed')}</p>
      ) : null}
    </div>
  )
}

/** Generates a fresh Ed25519 key and shows its public half so the user can copy it. */
function AddSshKeyForm() {
  const t = useT()
  const create = useCreateSshKey()
  const [host, setHost] = useState('')
  const [username, setUsername] = useState('')
  const [comment, setComment] = useState('')

  const canSubmit = host.trim() !== '' && !create.isPending

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    create.mutate(
      {
        host: host.trim(),
        username: username.trim() || undefined,
        comment: comment.trim() || undefined,
      },
      {
        onSuccess: () => {
          setHost('')
          setUsername('')
          setComment('')
        },
      },
    )
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3 rounded-lg border border-border bg-elevated p-4">
      <div>
        <h3 className="text-sm font-medium text-primary">{t('settings.credentials.sshTitle')}</h3>
        <p className="mt-1 text-xs text-muted">{t('settings.credentials.sshDescription')}</p>
      </div>

      <div className="flex flex-wrap gap-3">
        <label className="flex min-w-40 flex-1 flex-col gap-1">
          <span className="text-xs text-muted">{t('settings.credentials.host')}</span>
          <input
            value={host}
            onChange={(e) => setHost(e.target.value)}
            placeholder={t('settings.credentials.hostPlaceholder')}
            className={inputClass}
          />
        </label>
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
          <span className="text-xs text-muted">{t('settings.credentials.sshComment')}</span>
          <input
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder={t('settings.credentials.sshCommentPlaceholder')}
            className={inputClass}
          />
        </label>
      </div>

      {create.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('settings.credentials.sshGenerateFailed')}: {t(apiErrorKey(create.error))}
        </p>
      ) : null}

      {create.data ? (
        <div className="flex flex-col gap-1">
          <span className="text-xs text-muted">{t('settings.credentials.sshPublicKeyLabel')}</span>
          <PublicKeyDisplay publicKey={create.data.publicKey ?? ''} />
        </div>
      ) : null}

      <div>
        <Button type="submit" disabled={!canSubmit}>
          {create.isPending
            ? t('settings.credentials.sshGenerating')
            : t('settings.credentials.sshGenerate')}
        </Button>
      </div>
    </form>
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
            placeholder={t('settings.credentials.hostPlaceholder')}
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
  // The AI key rides the same credential row shape but has its own panel; keep it out
  // of this list so it isn't shown twice or deletable from the wrong place.
  const rows = credentials.data?.filter((c) => !isAiKeyCredential(c))

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
      <AddSshKeyForm />

      {credentials.isPending ? (
        <p className="flex items-center gap-2 text-xs text-muted">
          <Spinner />
          {t('settings.credentials.loading')}
        </p>
      ) : credentials.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('settings.credentials.loadFailed')}: {t(apiErrorKey(credentials.error))}
        </p>
      ) : rows && rows.length === 0 ? (
        <p className="text-xs text-muted">{t('settings.credentials.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {rows?.map((credential) => (
            <CredentialRow key={credential.id} credential={credential} />
          ))}
        </ul>
      )}
    </section>
  )
}
