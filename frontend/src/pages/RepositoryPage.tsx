import { useUiStore } from '@/shared/lib/uiStore'
import { Pane, SplitHandle, SplitPane } from '@/shared/ui'
import { BottomPanel, BottomPanelHeader } from '@/widgets/BottomPanel'
import { CenterPanel } from '@/widgets/CenterPanel'
import { DiffPanel } from '@/widgets/DiffPanel'
import { Sidebar } from '@/widgets/Sidebar'
import { Toolbar } from '@/widgets/Toolbar'

/**
 * Repository shell (docs/06 §1):
 * Toolbar / (Sidebar | Center | Diff) / collapsible Bottom panel.
 * All pane borders are drag-resizable; sizes persist to `ui_state` in a
 * later milestone via SplitPane's layout callbacks.
 */
export function RepositoryPage() {
  const bottomCollapsed = useUiStore((s) => s.bottomPanelCollapsed)

  return (
    <div className="flex h-full min-h-0 flex-col">
      <Toolbar />

      <div className="min-h-0 flex-1">
        <SplitPane orientation="vertical" id="repository-vertical">
          <Pane id="workspace" minSize="160px">
            <SplitPane orientation="horizontal" id="repository-horizontal">
              <Pane id="sidebar" defaultSize="220px" minSize="160px" maxSize="380px">
                <Sidebar />
              </Pane>
              <SplitHandle direction="vertical" />
              <Pane id="center" minSize="280px">
                <CenterPanel />
              </Pane>
              <SplitHandle direction="vertical" />
              <Pane id="diff" defaultSize={32} minSize="220px">
                <DiffPanel />
              </Pane>
            </SplitPane>
          </Pane>

          {!bottomCollapsed && (
            <>
              <SplitHandle direction="horizontal" />
              <Pane id="bottom" defaultSize="200px" minSize="120px" maxSize={60}>
                <BottomPanel />
              </Pane>
            </>
          )}
        </SplitPane>
      </div>

      {bottomCollapsed && (
        <div className="shrink-0 border-t border-border bg-panel">
          <BottomPanelHeader />
        </div>
      )}
    </div>
  )
}
