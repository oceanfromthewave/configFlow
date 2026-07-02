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
    </header>
  )
}
