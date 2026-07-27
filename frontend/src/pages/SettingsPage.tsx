import { useT } from '@/shared/i18n'
import { CredentialsPanel } from '@/widgets/CredentialsPanel'

/** App-global settings. Credentials are the only section for now (docs/07 §125). */
export function SettingsPage() {
  const t = useT()

  return (
    <div className="mx-auto flex h-full max-w-2xl flex-col gap-6 overflow-y-auto p-6">
      <h1 className="text-lg font-semibold text-primary">{t('settings.title')}</h1>
      <CredentialsPanel />
    </div>
  )
}
