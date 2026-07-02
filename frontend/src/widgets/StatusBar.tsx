import { useHealth } from '@/shared/api'
import { useT } from '@/shared/i18n'
import { cn } from '@/shared/lib/cn'

function BackendIndicator() {
  const t = useT()
  const health = useHealth()

  const state = health.isPending
    ? 'checking'
    : health.isSuccess
      ? 'connected'
      : 'disconnected'

  const label =
    state === 'connected'
      ? t('statusBar.backendConnected')
      : state === 'checking'
        ? t('statusBar.backendChecking')
        : t('statusBar.backendDisconnected')

  return (
    <span className="flex items-center gap-1.5" role="status">
      <span
        aria-hidden
        className={cn(
          'h-2 w-2 rounded-full',
          state === 'connected' && 'bg-vcs-added',
          state === 'disconnected' && 'bg-vcs-deleted',
          state === 'checking' && 'animate-pulse bg-vcs-modified',
        )}
      />
      {label}
    </span>
  )
}

/** StatusBar (docs/06 §1): branch, ahead/behind, operations, backend health. */
export function StatusBar() {
  const t = useT()

  return (
    <footer className="flex h-6 shrink-0 select-none items-center justify-between border-t border-border bg-panel px-3 text-xs text-muted">
      <span aria-hidden="false">
        {'⑂'} {t('statusBar.noBranch')}
      </span>
      <BackendIndicator />
    </footer>
  )
}
