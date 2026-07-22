import { useMemo, type ReactNode } from 'react'

import { useRefs } from '@/entities/repository/api/repositories'
import type { RefLabel } from '@/entities/repository/model/types'
import { useT } from '@/shared/i18n'
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
}: {
  name: string
  current: boolean
  currentLabel: string
}) {
  return (
    <div
      title={name}
      aria-current={current}
      className={cn(
        'flex h-6 select-none items-center gap-1.5 rounded px-2 text-[13px] hover:bg-elevated',
        current ? 'text-primary' : 'text-primary/90',
      )}
    >
      {/* A dot rather than bold text: the row stays the same width either way. */}
      {current ? (
        <span aria-label={currentLabel} title={currentLabel} className="text-accent">
          ●
        </span>
      ) : null}
      <span className="min-w-0 truncate">{name}</span>
    </div>
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

  function renderRefs(names: string[], markCurrent: boolean) {
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
    return names.map((name) => (
      <RefItem
        key={name}
        name={name}
        current={markCurrent && name === head}
        currentLabel={t('sidebar.currentBranch')}
      />
    ))
  }

  return (
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
        {renderRefs(local, true)}
      </Section>

      <Section title={t('sidebar.remote')}>{renderRefs(remote, false)}</Section>

      <Section title={t('sidebar.tags')}>{renderRefs(tags, false)}</Section>
    </nav>
  )
}
