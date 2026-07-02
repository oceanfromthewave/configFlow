import type { RepositorySummary } from '@/entities/repository/model/types'
import { useT } from '@/shared/i18n'
import { Badge, Button, EmptyState } from '@/shared/ui'

/** Mock data until `GET /repositories` is wired up in a later milestone. */
const favoriteRepositories: RepositorySummary[] = []
const recentRepositories: RepositorySummary[] = []

function RepositoryGrid({
  repositories,
  emptyTitle,
  emptyDescription,
}: {
  repositories: RepositorySummary[]
  emptyTitle: string
  emptyDescription: string
}) {
  if (repositories.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-border">
        <EmptyState title={emptyTitle} description={emptyDescription} />
      </div>
    )
  }

  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-3">
      {repositories.map((repo) => (
        <button
          key={repo.id}
          type="button"
          className="flex flex-col gap-1 rounded-lg border border-border bg-elevated p-3 text-left outline-none hover:border-accent/60 focus-visible:ring-2 focus-visible:ring-accent/60"
        >
          <span className="flex items-center gap-2 text-sm font-medium text-primary">
            {repo.name}
            <Badge variant={repo.vcsType === 'GIT' ? 'accent' : 'renamed'}>
              {repo.vcsType}
            </Badge>
          </span>
          <span className="truncate text-xs text-muted">{repo.localPath}</span>
        </button>
      ))}
    </div>
  )
}

/** Welcome screen (docs/06 §2): favorites + recent grid, 3 main actions. */
export function WelcomePage() {
  const t = useT()

  return (
    <div className="h-full overflow-y-auto bg-base">
      <div className="mx-auto flex max-w-3xl flex-col gap-8 px-8 py-12">
        <header className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold text-primary">
            {t('welcome.title')}
          </h1>
          <p className="text-sm text-muted">{t('welcome.subtitle')}</p>
        </header>

        <div className="flex gap-2">
          <Button variant="primary" disabled title={t('welcome.comingSoon')}>
            {t('welcome.clone')}
          </Button>
          <Button disabled title={t('welcome.comingSoon')}>
            {t('welcome.addLocal')}
          </Button>
          <Button disabled title={t('welcome.comingSoon')}>
            {t('welcome.init')}
          </Button>
        </div>

        <section className="flex flex-col gap-2">
          <h2 className="select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
            {t('welcome.favorites')}
          </h2>
          <RepositoryGrid
            repositories={favoriteRepositories}
            emptyTitle={t('welcome.favoritesEmptyTitle')}
            emptyDescription={t('welcome.favoritesEmptyDescription')}
          />
        </section>

        <section className="flex flex-col gap-2">
          <h2 className="select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
            {t('welcome.recent')}
          </h2>
          <RepositoryGrid
            repositories={recentRepositories}
            emptyTitle={t('welcome.recentEmptyTitle')}
            emptyDescription={t('welcome.recentEmptyDescription')}
          />
        </section>
      </div>
    </div>
  )
}
