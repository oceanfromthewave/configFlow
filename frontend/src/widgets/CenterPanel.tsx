import {useT} from '@/shared/i18n'
import {useUiStore, type CenterTab} from '@/shared/lib/uiStore'
import {Tabs} from '@/shared/ui'
import {CommitBox} from '@/widgets/CommitBox'
import {HistoryPanel} from '@/widgets/HistoryPanel'
import {WorkingTreePanel} from '@/widgets/WorkingTreePanel'

/** Center area (docs/06 §1): [History | Working Tree] tabs + content. */
export function CenterPanel() {
    const t = useT()
    const centerTab = useUiStore((s) => s.centerTab)
    const setCenterTab = useUiStore((s) => s.setCenterTab)

    const tabs = [
        {id: 'history' as CenterTab, label: t('center.tabHistory')},
        {id: 'workingTree' as CenterTab, label: t('center.tabWorkingTree')},
    ]

    return (
        <div className="flex h-full flex-col bg-base">
            <div className="flex h-9 shrink-0 items-center border-b border-border px-2">
                <Tabs
                    items={tabs}
                    active={centerTab}
                    onChange={setCenterTab}
                    size="sm"
                    aria-label={t('center.tabHistory')}
                />
            </div>
            <div className="min-h-0 flex-1">
                {centerTab === 'workingTree' ? (
                    <div className="flex h-full flex-col">
                        <div className="min-h-0 flex-1">
                            <WorkingTreePanel/>
                        </div>
                        <CommitBox/>
                    </div>
                ) : (
                    <HistoryPanel/>
                )}
            </div>
        </div>
    )
}