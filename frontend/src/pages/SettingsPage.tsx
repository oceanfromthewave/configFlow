import { useT } from '@/shared/i18n'
import { AiKeyPanel } from '@/widgets/AiKeyPanel'
import { CredentialsPanel } from '@/widgets/CredentialsPanel'
import { ProxyPanel } from '@/widgets/ProxyPanel'

/** App-global settings (docs/07 §125). */
export function SettingsPage() {
  const t = useT()

  return (
    <div className="mx-auto flex h-full max-w-2xl flex-col gap-6 overflow-y-auto p-6">
      <h1 className="text-lg font-semibold text-primary">{t('settings.title')}</h1>
      <CredentialsPanel />
      <ProxyPanel />
      <AiKeyPanel />
    </div>
  )
}
