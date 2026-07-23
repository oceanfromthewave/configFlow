import { useT } from '@/shared/i18n'
import { useUiStore, type BottomPanelTab } from '@/shared/lib/uiStore'
import { IconButton, Tabs } from '@/shared/ui'
import { OperationsPanel } from '@/widgets/OperationsPanel'

/** Always-visible header row: tabs + collapse/expand toggle. */
export function BottomPanelHeader() {
  const t = useT()
  const collapsed = useUiStore((s) => s.bottomPanelCollapsed)
  const toggle = useUiStore((s) => s.toggleBottomPanel)
  const activeTab = useUiStore((s) => s.bottomPanelTab)
  const setTab = useUiStore((s) => s.setBottomPanelTab)

  const tabs = [
    { id: 'console' as BottomPanelTab, label: t('bottomPanel.console') },
    { id: 'operations' as BottomPanelTab, label: t('bottomPanel.operations') },
    { id: 'log' as BottomPanelTab, label: t('bottomPanel.log') },
  ]

  return (
    <div className="flex h-8 shrink-0 items-center justify-between border-b border-border px-2">
      <Tabs
        items={tabs}
        active={activeTab}
        onChange={(tab) => {
          setTab(tab)
          if (collapsed) toggle()
        }}
        size="sm"
        aria-label={t('bottomPanel.console')}
      />
      <IconButton
        size="sm"
        aria-label={collapsed ? t('bottomPanel.expand') : t('bottomPanel.collapse')}
        aria-expanded={!collapsed}
        onClick={toggle}
      >
        <span aria-hidden>{collapsed ? '⌃' : '⌄'}</span>
      </IconButton>
    </div>
  )
}

const emptyMessageKey = {
  console: 'bottomPanel.consoleEmpty',
  operations: 'bottomPanel.operationsEmpty',
  log: 'bottomPanel.logEmpty',
} as const

/**
 * Collapsible bottom panel (docs/06 §1): Console / Operations / Log.
 * When collapsed only the header bar is rendered (by RepositoryPage).
 */
export function BottomPanel() {
  const t = useT()
  const activeTab = useUiStore((s) => s.bottomPanelTab)

  return (
    <div className="flex h-full flex-col bg-panel">
      <BottomPanelHeader />
      <div
        role="tabpanel"
        className="min-h-0 flex-1 overflow-y-auto p-3 font-mono text-xs text-muted"
      >
        {activeTab === 'operations' ? (
          <OperationsPanel />
        ) : (
          t(emptyMessageKey[activeTab])
        )}
      </div>
    </div>
  )
}
