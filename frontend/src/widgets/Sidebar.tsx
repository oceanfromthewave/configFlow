import type { ReactNode } from 'react'

import { useT } from '@/shared/i18n'

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

/**
 * Left sidebar (docs/06 §1): WORKSPACE / BRANCHES / TAGS.
 * STASHES / SVN LOCKS sections appear later, gated by Capability.
 */
export function Sidebar() {
  const t = useT()

  return (
    <nav className="h-full overflow-y-auto bg-panel py-1">
      <Section title={t('sidebar.workspace')}>
        <SectionItem>{t('sidebar.fileStatus')}</SectionItem>
        <SectionItem>{t('sidebar.history')}</SectionItem>
        <SectionItem>{t('sidebar.search')}</SectionItem>
      </Section>

      <Section title={t('sidebar.branches')}>
        <SectionItem>{t('sidebar.local')}</SectionItem>
        <SectionItem>{t('sidebar.remote')}</SectionItem>
      </Section>

      <Section title={t('sidebar.tags')}>
        <SectionEmpty>{t('sidebar.emptySection')}</SectionEmpty>
      </Section>
    </nav>
  )
}
