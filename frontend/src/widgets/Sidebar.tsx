import {
  useEffect,
  useMemo,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from 'react'

import {
  useCheckout,
  useMerge,
  useRefs,
} from '@/entities/repository/api/repositories'
import type { RefLabel } from '@/entities/repository/model/types'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { cn } from '@/shared/lib/cn'
import { useUiStore } from '@/shared/lib/uiStore'
import { Spinner } from '@/shared/ui'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="px-3 py-2">
      <h2 className="mb-1 select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
        {title}
      </h2>
      {children}
    </section>
  )
}

function SectionItem({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-6 select-none items-center rounded px-2 text-[13px] text-primary/90 hover:bg-elevated">
      {children}
    </div>
  )
}

function SectionEmpty({ children }: { children: ReactNode }) {
  return (
    <p className="select-none px-2 py-0.5 text-xs italic text-muted/80">
      {children}
    </p>
  )
}

function RefItem({
  name,
  current,
  currentLabel,
  onCheckout,
  checkoutLabel,
  onContextMenu,
  disabled,
}: {
  name: string
  current: boolean
  currentLabel: string
  onCheckout?: () => void
  checkoutLabel: string
  onContextMenu?: (event: ReactMouseEvent) => void
  disabled: boolean
}) {
  const className = cn(
    'flex h-6 w-full select-none items-center gap-1.5 rounded px-2 text-left text-[13px] hover:bg-elevated',
    current ? 'text-primary' : 'text-primary/90',
  )
  const content = (
    <>
      {/* A dot rather than bold text: the row stays the same width either way. */}
      {current ? (
        <span aria-label={currentLabel} title={currentLabel} className="text-accent">
          ●
        </span>
      ) : null}
      <span className="min-w-0 truncate">{name}</span>
    </>
  )

  // Only a branch you are not already on is worth clicking.
  if (onCheckout == null || current) {
    return (
      <div
        title={name}
        aria-current={current}
        onContextMenu={onContextMenu}
        className={className}
      >
        {content}
      </div>
    )
  }

  return (
    <button
      type="button"
      title={`${checkoutLabel}: ${name}`}
      aria-label={`${checkoutLabel}: ${name}`}
      disabled={disabled}
      onClick={onCheckout}
      onContextMenu={onContextMenu}
      className={cn(
        className,
        'outline-none focus-visible:ring-2 focus-visible:ring-accent/60 disabled:cursor-not-allowed disabled:opacity-50',
      )}
    >
      {content}
    </button>
  )
}

/**
 * Left sidebar (docs/06 §1): WORKSPACE / BRANCHES / TAGS.
 * STASHES / SVN LOCKS sections appear later, gated by Capability.
 */
export function Sidebar() {
  const t = useT()
  const repositoryId = useUiStore((s) => s.currentRepositoryId)
  const refs = useRefs(repositoryId)
  const checkout = useCheckout()
  const merge = useMerge()

  // The branch a right-click opened the merge menu on, plus where to anchor it.
  const [menu, setMenu] = useState<{ name: string; x: number; y: number } | null>(
    null,
  )

  const { head, local, remote, tags } = useMemo(() => {
    const all: RefLabel[] = refs.data?.refs ?? []
    const of = (kind: RefLabel['kind']) =>
      all.filter((ref) => ref.kind === kind).map((ref) => ref.name)
    return {
      head: all.find((ref) => ref.kind === 'HEAD')?.name ?? null,
      local: of('BRANCH'),
      remote: of('REMOTE_BRANCH'),
      tags: of('TAG'),
    }
  }, [refs.data])

  // HEAD names a branch while attached; when detached it carries a revision id,
  // which never matches a branch name.
  const detached = head != null && !local.includes(head)
  // Merging needs a branch to merge *into*; a detached head has none.
  const canMerge = repositoryId != null && !detached

  // A stale menu anchored to a branch that no longer exists would be misleading,
  // so close it whenever a merge cannot target a current branch any more.
  useEffect(() => {
    if (!canMerge && menu != null) setMenu(null)
  }, [canMerge, menu])

  // Escape and scrolling both dismiss the open menu — the position is fixed, so
  // scrolling the list would otherwise leave it stranded.
  useEffect(() => {
    if (menu == null) return
    const close = () => setMenu(null)
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('scroll', close, true)
    }
  }, [menu])

  function openMenu(name: string, event: ReactMouseEvent) {
    // Suppress the browser's own menu; ours takes its place.
    event.preventDefault()
    setMenu({ name, x: event.clientX, y: event.clientY })
  }

  function runMerge() {
    if (menu == null || repositoryId == null) return
    merge.mutate({ repositoryId, source: menu.name })
    setMenu(null)
  }

  function renderRefs(
    names: string[],
    markCurrent: boolean,
    checkoutable = false,
    mergeable = false,
  ) {
    if (repositoryId == null) {
      return <SectionEmpty>{t('sidebar.noRepository')}</SectionEmpty>
    }
    if (refs.isPending) {
      return (
        <p className="flex items-center gap-1.5 px-2 py-0.5 text-xs text-muted">
          <Spinner />
          {t('sidebar.refsLoading')}
        </p>
      )
    }
    if (refs.isError) {
      return (
        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
          {t('sidebar.refsLoadFailed')}
        </p>
      )
    }
    if (names.length === 0) {
      return <SectionEmpty>{t('sidebar.emptySection')}</SectionEmpty>
    }
    return names.map((name) => {
      const current = markCurrent && name === head
      return (
        <RefItem
          key={name}
          name={name}
          current={current}
          currentLabel={t('sidebar.currentBranch')}
          checkoutLabel={t('branch.checkout')}
          disabled={checkout.isPending}
          onCheckout={
            checkoutable ? () => checkout.mutate({ repositoryId, ref: name }) : undefined
          }
          // Merging a branch into itself is a no-op, so the current one is skipped.
          onContextMenu={
            mergeable && canMerge && !current
              ? (event) => openMenu(name, event)
              : undefined
          }
        />
      )
    })
  }

  return (
    <>
      <nav className="h-full overflow-y-auto bg-panel py-1">
        <Section title={t('sidebar.workspace')}>
          <SectionItem>{t('sidebar.fileStatus')}</SectionItem>
          <SectionItem>{t('sidebar.history')}</SectionItem>
          <SectionItem>{t('sidebar.search')}</SectionItem>
        </Section>

        <Section title={t('sidebar.branches')}>
          {detached ? (
            <p className="px-2 py-0.5 text-xs text-vcs-modified">
              {t('sidebar.detachedHead')}: {head?.slice(0, 7)}
            </p>
          ) : null}
          {checkout.isError ? (
            <p className="px-2 py-0.5 text-xs text-vcs-deleted">
              {t('branch.checkoutFailed')}: {t(apiErrorKey(checkout.error))}
            </p>
          ) : null}
          {merge.isError ? (
            <p className="px-2 py-0.5 text-xs text-vcs-deleted">
              {t('branch.mergeFailed')}: {t(apiErrorKey(merge.error))}
            </p>
          ) : null}
          {renderRefs(local, true, true, true)}
        </Section>

        <Section title={t('sidebar.remote')}>{renderRefs(remote, false)}</Section>

        <Section title={t('sidebar.tags')}>{renderRefs(tags, false)}</Section>
      </nav>

      {menu != null ? (
        <>
          {/* A full-screen catcher closes the menu on any outside click. */}
          <div
            className="fixed inset-0 z-40"
            onClick={() => setMenu(null)}
            onContextMenu={(event) => {
              event.preventDefault()
              setMenu(null)
            }}
          />
          <div
            role="menu"
            style={{ top: menu.y, left: menu.x }}
            className="fixed z-50 min-w-max rounded-md border border-border bg-elevated py-1 text-[13px] shadow-lg"
          >
            <button
              type="button"
              role="menuitem"
              disabled={merge.isPending}
              onClick={runMerge}
              className="block w-full px-3 py-1 text-left text-primary/90 outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
            >
              {t('branch.mergeInto', { source: menu.name })}
            </button>
          </div>
        </>
      ) : null}
    </>
  )
}
