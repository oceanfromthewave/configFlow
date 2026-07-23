import {
  useFetch,
  usePull,
  usePush,
} from '@/entities/repository/api/repositories'
import { useOperations } from '@/entities/operation/api/operations'
import { isTerminal } from '@/entities/operation/model/types'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { useUiStore } from '@/shared/lib/uiStore'
import { Button, Tooltip } from '@/shared/ui'

/**
 * Toolbar (docs/06 §1): the remote commands, plus placeholders for what has not
 * landed yet. The button set will later be driven by the repository's Capability
 * set rather than hard-coded.
 */
export function Toolbar() {
  const t = useT()
  const repositoryId = useUiStore((s) => s.currentRepositoryId)
  const setBottomPanelTab = useUiStore((s) => s.setBottomPanelTab)
  const bottomPanelCollapsed = useUiStore((s) => s.bottomPanelCollapsed)
  const toggleBottomPanel = useUiStore((s) => s.toggleBottomPanel)

  const fetch = useFetch()
  const pull = usePull()
  const push = usePush()
  const operations = useOperations(repositoryId)

  // Anything unfinished on this repository will be queued behind, so the button
  // would sit there doing nothing visible. Better to say it is busy.
  const busy =
    fetch.isPending ||
    pull.isPending ||
    push.isPending ||
    (operations.data ?? []).some((operation) => !isTerminal(operation.state))

  const failure = fetch.error ?? pull.error ?? push.error

  /** Sends the command and reveals the queue, which is where the answer shows up. */
  function run(start: () => void) {
    if (repositoryId == null) return
    fetch.reset()
    pull.reset()
    push.reset()
    start()
    setBottomPanelTab('operations')
    if (bottomPanelCollapsed) {
      toggleBottomPanel()
    }
  }

  const actions = [
    {
      id: 'pull',
      label: t('toolbar.pull'),
      onClick: () => run(() => pull.mutate({ repositoryId: repositoryId!, strategy: 'MERGE' })),
    },
    {
      id: 'push',
      label: t('toolbar.push'),
      onClick: () => run(() => push.mutate({ repositoryId: repositoryId! })),
    },
    {
      id: 'fetch',
      label: t('toolbar.fetch'),
      onClick: () => run(() => fetch.mutate({ repositoryId: repositoryId!, prune: false })),
    },
  ] as const

  return (
    <div className="flex h-10 shrink-0 select-none items-center gap-1 border-b border-border bg-panel px-2">
      {actions.map((action) => (
        <Button
          key={action.id}
          variant="ghost"
          size="sm"
          disabled={repositoryId == null || busy}
          onClick={action.onClick}
        >
          {action.label}
        </Button>
      ))}

      <Tooltip label={t('toolbar.comingSoon')}>
        <Button variant="ghost" size="sm" disabled>
          {t('toolbar.branch')}
        </Button>
      </Tooltip>

      {failure ? (
        <span className="ml-2 truncate text-xs text-vcs-deleted">
          {t(apiErrorKey(failure))}
        </span>
      ) : null}
    </div>
  )
}
