import { RepositoryPage } from '@/pages/RepositoryPage'
import { SettingsPage } from '@/pages/SettingsPage'
import { WelcomePage } from '@/pages/WelcomePage'
import { useUiStore } from '@/shared/lib/uiStore'
import { StatusBar } from '@/widgets/StatusBar'
import { TitleBar } from '@/widgets/TitleBar'

/**
 * App frame: TitleBar and StatusBar are always visible; the middle region
 * switches between Welcome and the Repository shell (state-based routing,
 * see shared/lib/uiStore.ts for the rationale).
 */
export function App() {
  const route = useUiStore((s) => s.route)

  return (
    <div className="flex h-full flex-col bg-base text-primary">
      <TitleBar />
      <main className="min-h-0 flex-1">
        {route === 'welcome' ? (
          <WelcomePage />
        ) : route === 'settings' ? (
          <SettingsPage />
        ) : (
          <RepositoryPage />
        )}
      </main>
      <StatusBar />
    </div>
  )
}
