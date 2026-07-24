import {
  useCancelOperation,
  useOperations,
} from '@/entities/operation/api/operations'
import type {
  Operation,
  OperationFailure,
  OperationState,
  OperationType,
} from '@/entities/operation/model/types'
import { isTerminal } from '@/entities/operation/model/types'
import { useT } from '@/shared/i18n'
import type { MessageKey } from '@/shared/i18n'
import { apiErrorKey, errorCodeKey } from '@/shared/lib/apiErrorMessage'
import { useUiStore } from '@/shared/lib/uiStore'
import { Badge, Button, Spinner } from '@/shared/ui'
import type { BadgeVariant } from '@/shared/ui'

const STATE_VARIANT: Record<OperationState, BadgeVariant> = {
  QUEUED: 'default',
  RUNNING: 'accent',
  SUCCEEDED: 'added',
  FAILED: 'deleted',
  CANCELLED: 'default',
}

/** Types worth naming; anything else falls back to a generic label. */
const NAMED_TYPES: readonly OperationType[] = [
  'CHECKOUT',
  'BRANCH_CREATE',
  'BRANCH_DELETE',
  'COMMIT',
  'MERGE',
]

function typeKey(type: OperationType): MessageKey {
  return (NAMED_TYPES.includes(type)
    ? `operations.type${type}`
    : 'operations.typeOTHER') as MessageKey
}

/**
 * `protocol://host` for an auth failure, otherwise nothing.
 *
 * "Authentication is required" on its own leaves the user guessing which remote
 * rejected them, and a repository can have several.
 */
function authTarget(failure: OperationFailure): string | null {
  const { host, protocol } = failure.context ?? {}
  return host ? `${protocol ? `${protocol}://` : ''}${host}` : null
}

function OperationRow({ operation }: { operation: Operation }) {
  const t = useT()
  const cancel = useCancelOperation()
  // Queued counts too — it has not started, so it is the easiest thing to stop.
  const cancellable = !isTerminal(operation.state)
  const percent = operation.progress?.percent ?? null

  return (
    <li className="flex flex-col gap-1 rounded px-2 py-1.5 hover:bg-elevated">
      <div className="flex items-center gap-2">
        <Badge variant={STATE_VARIANT[operation.state]}>
          {t(`operations.state${operation.state}` as MessageKey)}
        </Badge>
        <span className="text-xs text-primary">{t(typeKey(operation.type))}</span>
        {operation.progress?.phase ? (
          <span className="min-w-0 flex-1 truncate text-[11px] text-muted">
            {operation.progress.phase}
            {operation.progress.detail ? ` · ${operation.progress.detail}` : ''}
          </span>
        ) : (
          <span className="flex-1" />
        )}
        {percent != null ? (
          <span className="shrink-0 font-mono text-[11px] text-muted">{percent}%</span>
        ) : null}
        {cancellable ? (
          <Button
            size="sm"
            variant="ghost"
            disabled={cancel.isPending}
            onClick={() => cancel.mutate(operation.operationId)}
          >
            {cancel.isPending ? t('operations.cancelling') : t('operations.cancel')}
          </Button>
        ) : null}
      </div>

      {operation.error ? (
        // The code decides what the user reads. `detail` is JGit's English and
        // often names an absolute path, so it stays in the tooltip.
        <p
          className="px-1 text-[11px] text-vcs-deleted"
          title={operation.error.detail ?? undefined}
        >
          {t(errorCodeKey(operation.error.code))}
          {authTarget(operation.error) ? ` · ${authTarget(operation.error)}` : ''}
        </p>
      ) : null}
    </li>
  )
}

/** Operations tab of the bottom panel (docs/06 §1): what the queue is doing. */
export function OperationsPanel() {
  const t = useT()
  const repositoryId = useUiStore((s) => s.currentRepositoryId)
  const operations = useOperations(repositoryId)

  if (operations.isPending) {
    return (
      <p className="flex items-center gap-2 text-xs text-muted">
        <Spinner />
        {t('operations.loading')}
      </p>
    )
  }

  if (operations.isError) {
    return (
      <p className="text-xs text-vcs-deleted">
        {t('operations.loadFailed')}: {t(apiErrorKey(operations.error))}
      </p>
    )
  }

  if (operations.data.length === 0) {
    return <p className="text-xs text-muted">{t('bottomPanel.operationsEmpty')}</p>
  }

  return (
    <ul className="flex flex-col gap-0.5">
      {operations.data.map((operation) => (
        <OperationRow key={operation.operationId} operation={operation} />
      ))}
    </ul>
  )
}
