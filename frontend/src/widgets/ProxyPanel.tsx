import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'

import { useDeleteProxy, useProxySettings, useSaveProxy } from '@/entities/proxy/api/proxy'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { Button, Spinner } from '@/shared/ui'

const inputClass =
  'rounded-md border border-border bg-base px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60'

/** Proxy settings (docs/06 §74): one URL + bypass list, applied on save. */
export function ProxyPanel() {
  const t = useT()
  const proxy = useProxySettings()
  const save = useSaveProxy()
  const del = useDeleteProxy()
  const [url, setUrl] = useState('')
  const [bypass, setBypass] = useState('')

  // The form mirrors whatever is stored; re-sync whenever the query resolves or changes
  // underneath us (e.g. after a successful delete).
  useEffect(() => {
    if (proxy.data) {
      setUrl(proxy.data.url ?? '')
      setBypass(proxy.data.bypass)
    }
  }, [proxy.data])

  const canSubmit = url.trim() !== '' && !save.isPending

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    save.mutate({ url: url.trim(), bypass: bypass.trim() || undefined })
  }

  return (
    <section className="flex flex-col gap-4">
      <div>
        <h2 className="text-sm font-medium text-primary">{t('settings.proxy.title')}</h2>
        <p className="mt-1 text-xs text-muted">{t('settings.proxy.description')}</p>
      </div>

      {proxy.isPending ? (
        <p className="flex items-center gap-2 text-xs text-muted">
          <Spinner />
          {t('settings.proxy.loading')}
        </p>
      ) : proxy.isError ? (
        <p className="text-xs text-vcs-deleted">
          {t('settings.proxy.loadFailed')}: {t(apiErrorKey(proxy.error))}
        </p>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-3 rounded-lg border border-border bg-elevated p-4">
          {proxy.data?.url ? (
            <p className="text-xs text-muted">
              {t('settings.proxy.active')}: <span className="text-primary">{proxy.data.url}</span>
            </p>
          ) : (
            <p className="text-xs text-muted">{t('settings.proxy.notConfigured')}</p>
          )}

          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted">{t('settings.proxy.url')}</span>
            <input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder={t('settings.proxy.urlPlaceholder')}
              className={inputClass}
            />
          </label>

          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted">{t('settings.proxy.bypass')}</span>
            <input
              value={bypass}
              onChange={(e) => setBypass(e.target.value)}
              placeholder={t('settings.proxy.bypassPlaceholder')}
              className={inputClass}
            />
          </label>
          <span className="-mt-2 text-xs text-muted">{t('settings.proxy.bypassHint')}</span>

          {save.isError ? (
            <p className="text-xs text-vcs-deleted">
              {t('settings.proxy.saveFailed')}: {t(apiErrorKey(save.error))}
            </p>
          ) : null}
          {del.isError ? (
            <p className="text-xs text-vcs-deleted">
              {t('settings.proxy.removeFailed')}: {t(apiErrorKey(del.error))}
            </p>
          ) : null}

          <div className="flex gap-2">
            <Button type="submit" disabled={!canSubmit}>
              {save.isPending ? t('settings.proxy.saving') : t('settings.proxy.save')}
            </Button>
            {proxy.data?.url ? (
              <Button
                type="button"
                variant="ghost"
                disabled={del.isPending}
                onClick={() => del.mutate()}
              >
                {del.isPending ? t('settings.proxy.removing') : t('settings.proxy.remove')}
              </Button>
            ) : null}
          </div>
        </form>
      )}
    </section>
  )
}
