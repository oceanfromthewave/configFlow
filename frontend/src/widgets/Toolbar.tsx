import { useT } from '@/shared/i18n'
import { Button, Tooltip } from '@/shared/ui'

/**
 * Toolbar (docs/06 §1). M0 placeholder: sync/branch actions are disabled
 * until the corresponding features land; button set will later be driven
 * by the repository's Capability set.
 */
export function Toolbar() {
  const t = useT()

  const actions = [
    { id: 'pull', label: t('toolbar.pull') },
    { id: 'push', label: t('toolbar.push') },
    { id: 'fetch', label: t('toolbar.fetch') },
    { id: 'branch', label: t('toolbar.branch') },
  ] as const

  return (
    <div className="flex h-10 shrink-0 select-none items-center gap-1 border-b border-border bg-panel px-2">
      {actions.map((action) => (
        <Tooltip key={action.id} label={t('toolbar.comingSoon')}>
          <Button variant="ghost" size="sm" disabled>
            {action.label}
          </Button>
        </Tooltip>
      ))}
    </div>
  )
}
