import { useT } from '@/shared/i18n'
import { EmptyState } from '@/shared/ui'

/** Right panel (docs/06 §1): diff viewer / commit details placeholder. */
export function DiffPanel() {
  const t = useT()

  return (
    <div className="h-full bg-panel">
      <EmptyState
        icon={<span aria-hidden>&#x00B1;</span>}
        title={t('diff.emptyTitle')}
        description={t('diff.emptyDescription')}
      />
    </div>
  )
}
