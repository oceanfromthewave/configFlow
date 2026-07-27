import { useT } from '@/shared/i18n'
import { cn } from '@/shared/lib/cn'
import { useUiStore } from '@/shared/lib/uiStore'

/**
 * TitleBar: app name + repository tabs (docs/06 §1).
 * M0 placeholder: a single mock tab that switches to the repository shell;
 * real per-repo tabs arrive with the repository entity work.
 * Window controls (- □ x) are provided by Electron in the desktop build.
 */
export function TitleBar() {
  const t = useT()
  const route = useUiStore((s) => s.route)
  const setRoute = useUiStore((s) => s.setRoute)

  return (
    <header className="flex h-9 shrink-0 select-none items-center gap-4 border-b border-border bg-panel px-3">
      <button
        type="button"
        onClick={() => setRoute('welcome')}
        aria-label={t('titleBar.goWelcome')}
        className="rounded text-sm font-semibold tracking-tight text-primary outline-none hover:text-accent focus-visible:ring-2 focus-visible:ring-accent/60"
      >
        {t('app.name')}
      </button>

      {/* Repository tabs placeholder */}
      <div
        role="tablist"
        aria-label={t('titleBar.sampleRepoTab')}
        className="flex min-w-0 flex-1 items-center gap-1"
      >
        <button
          type="button"
          role="tab"
          aria-selected={route === 'repository'}
          onClick={() => setRoute('repository')}
          className={cn(
            'flex h-6 items-center gap-1.5 rounded-md border border-dashed px-2.5 text-xs outline-none transition-colors',
            'focus-visible:ring-2 focus-visible:ring-accent/60',
            route === 'repository'
              ? 'border-accent/60 bg-elevated text-primary'
              : 'border-border text-muted hover:bg-elevated hover:text-primary',
          )}
        >
          {t('titleBar.sampleRepoTab')}
        </button>
      </div>

      <button
        type="button"
        onClick={() => setRoute('settings')}
        aria-label={t('titleBar.settings')}
        aria-current={route === 'settings'}
        className={cn(
          'rounded p-1 outline-none hover:text-primary focus-visible:ring-2 focus-visible:ring-accent/60',
          route === 'settings' ? 'text-primary' : 'text-muted',
        )}
      >
        <svg
          aria-hidden="true"
          viewBox="0 0 24 24"
          className="h-4 w-4"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      </button>
    </header>
  )
}
