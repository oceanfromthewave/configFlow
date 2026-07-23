import { useState, type FormEvent } from 'react'

import {
  useOpenRepository,
  useRegisterRepository,
  useRepositories,
} from '@/entities/repository/api/repositories'
import type { RepositorySummary } from '@/entities/repository/model/types'
import { useT } from '@/shared/i18n'
import { apiErrorKey } from '@/shared/lib/apiErrorMessage'
import { canPickDirectory, pickDirectory } from '@/shared/lib/nativeBridge'
import { useUiStore } from '@/shared/lib/uiStore'
import { Badge, Button, EmptyState, Spinner } from '@/shared/ui'

function RepositoryGrid({
  repositories,
  emptyTitle,
  emptyDescription,
  onOpen,
}: {
  repositories: RepositorySummary[]
  emptyTitle: string
  emptyDescription: string
  onOpen: (repository: RepositorySummary) => void
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
          onClick={() => onOpen(repo)}
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

/** Welcome screen (docs/06 §2): favorites + recent, wired to the repository API. */
export function WelcomePage() {
  const t = useT()
  const showRepository = useUiStore((s) => s.openRepository)

  const repositories = useRepositories()
  const registerRepository = useRegisterRepository()
  const openRepository = useOpenRepository()

  const [pathFormOpen, setPathFormOpen] = useState(false)
  const [localPath, setLocalPath] = useState('')

  const all = repositories.data ?? []
  const favorites = all.filter((repo) => repo.favorite)

  function open(repository: RepositorySummary) {
    // Only switch the workspace once the server confirmed the repository is
    // still there: navigating first would land on a panel whose every query
    // fails (deleted folder, revoked permissions, ...).
    openRepository.mutate(repository.id, {
      onSuccess: () => showRepository(repository.id),
    })
  }

  /**
   * Picks a folder natively where that is possible, and falls back to typing a
   * path where it is not.
   *
   * A browser cannot hand back a real filesystem path — the File System Access
   * API deals in handles and `webkitdirectory` in relative names — so the text
   * field is not a lesser alternative, it is the only thing that works there.
   */
  async function addLocal() {
    if (!canPickDirectory()) {
      setPathFormOpen((isOpen) => !isOpen)
      return
    }
    registerRepository.reset()
    const picked = await pickDirectory()
    if (picked != null) {
      registerRepository.mutate(picked)
    }
  }

  function submitPath(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmed = localPath.trim()
    if (trimmed.length === 0) return
    registerRepository.mutate(trimmed, {
      onSuccess: () => {
        setLocalPath('')
        setPathFormOpen(false)
      },
    })
  }

  return (
    <div className="h-full overflow-y-auto bg-base">
      <div className="mx-auto flex max-w-3xl flex-col gap-8 px-8 py-12">
        <header className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold text-primary">
            {t('welcome.title')}
          </h1>
          <p className="text-sm text-muted">{t('welcome.subtitle')}</p>
        </header>

        <div className="flex flex-col gap-3">
          <div className="flex gap-2">
            <Button variant="primary" disabled title={t('welcome.comingSoon')}>
              {t('welcome.clone')}
            </Button>
            <Button onClick={addLocal} disabled={registerRepository.isPending}>
              {t('welcome.addLocal')}
            </Button>
            <Button disabled title={t('welcome.comingSoon')}>
              {t('welcome.init')}
            </Button>
          </div>

          {/* Only reachable outside Electron, where no OS picker exists. */}
          {pathFormOpen ? (
            <form onSubmit={submitPath} className="flex flex-col gap-2">
              <div className="flex gap-2">
                <input
                  autoFocus
                  value={localPath}
                  onChange={(event) => setLocalPath(event.target.value)}
                  placeholder={t('welcome.addLocalPlaceholder')}
                  aria-label={t('welcome.addLocalPlaceholder')}
                  className="flex-1 rounded-md border border-border bg-elevated px-3 py-1.5 text-sm text-primary outline-none placeholder:text-muted focus-visible:ring-2 focus-visible:ring-accent/60"
                />
                <Button
                  type="submit"
                  variant="primary"
                  disabled={registerRepository.isPending}
                >
                  {registerRepository.isPending
                    ? t('welcome.registering')
                    : t('welcome.addLocalSubmit')}
                </Button>
              </div>
              <p className="text-xs text-muted">{t('welcome.noPickerHint')}</p>
            </form>
          ) : null}

          {registerRepository.isPending && !pathFormOpen ? (
            <p className="text-xs text-muted">{t('welcome.registering')}</p>
          ) : null}

          {/* Outside the form: with the OS picker there is no form to attach it to. */}
          {registerRepository.isError ? (
            <p className="text-xs text-vcs-deleted">
              {t('welcome.registerFailed')}:{' '}
              {t(apiErrorKey(registerRepository.error))}
            </p>
          ) : null}
        </div>

        {repositories.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted">
            <Spinner />
            {t('welcome.loading')}
          </div>
        ) : repositories.isError ? (
          <p className="text-sm text-vcs-deleted">{t('welcome.loadFailed')}</p>
        ) : (
          <>
            {openRepository.isError ? (
              <p className="text-sm text-vcs-deleted">
                {t('welcome.openFailed')}: {t(apiErrorKey(openRepository.error))}
              </p>
            ) : null}

            <section className="flex flex-col gap-2">
              <h2 className="select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
                {t('welcome.favorites')}
              </h2>
              <RepositoryGrid
                repositories={favorites}
                emptyTitle={t('welcome.favoritesEmptyTitle')}
                emptyDescription={t('welcome.favoritesEmptyDescription')}
                onOpen={open}
              />
            </section>

            <section className="flex flex-col gap-2">
              <h2 className="select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
                {t('welcome.recent')}
              </h2>
              <RepositoryGrid
                repositories={all}
                emptyTitle={t('welcome.recentEmptyTitle')}
                emptyDescription={t('welcome.recentEmptyDescription')}
                onOpen={open}
              />
            </section>
          </>
        )}
      </div>
    </div>
  )
}
