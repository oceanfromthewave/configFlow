import { useT } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { Button } from '@/shared/ui'

/**
 * Destructive-action confirmation modal, mounted once at the app root.
 * Renders nothing until `uiStore.confirm` has a pending request — see
 * `requestConfirm` there for why this replaces `window.confirm`.
 */
export function ConfirmModal() {
  const t = useT()
  const confirm = useUiStore((s) => s.confirm)
  const resolveConfirm = useUiStore((s) => s.resolveConfirm)
  if (!confirm) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="flex w-full max-w-sm flex-col gap-3 rounded-lg border border-border bg-elevated p-4 shadow-lg">
        <p className="text-sm text-primary">{confirm.message}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => resolveConfirm(false)}>
            {t('confirmDialog.cancel')}
          </Button>
          <Button variant="primary" autoFocus onClick={() => resolveConfirm(true)}>
            {t('confirmDialog.confirm')}
          </Button>
        </div>
      </div>
    </div>
  )
}
