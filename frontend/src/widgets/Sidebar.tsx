import {
    useEffect,
    useMemo,
    useRef,
    useState,
    type FormEvent,
    type MouseEvent as ReactMouseEvent,
    type ReactNode,
} from 'react'

import {
    useCheckout,
    useCreateBranch,
    useDeleteBranch,
    useMerge,
    useRefs,
} from '@/entities/repository/api/repositories'
import type {RefLabel} from '@/entities/repository/model/types'
import {useT} from '@/shared/i18n'
import {apiErrorKey} from '@/shared/lib/apiErrorMessage'
import {cn} from '@/shared/lib/cn'
import {useUiStore} from '@/shared/lib/uiStore'
import {Spinner} from '@/shared/ui'

function Section({
                     title,
                     action,
                     children,
                 }: {
    title: string
    action?: ReactNode
    children: ReactNode
}) {
    return (
        <section className="px-3 py-2">
            <div className="mb-1 flex items-center justify-between">
                <h2 className="select-none text-[11px] font-semibold uppercase tracking-wider text-muted">
                    {title}
                </h2>
                {action}
            </div>
            {children}
        </section>
    )
}

function SectionItem({children}: { children: ReactNode }) {
    return (
        <div className="flex h-6 select-none items-center rounded px-2 text-[13px] text-primary/90 hover:bg-elevated">
            {children}
        </div>
    )
}

function SectionEmpty({children}: { children: ReactNode }) {
    return (
        <p className="select-none px-2 py-0.5 text-xs italic text-muted/80">
            {children}
        </p>
    )
}

/** Inline "create branch" row shown below the BRANCHES header. */
function NewBranchForm({
                           onSubmit,
                           onCancel,
                           pending,
                       }: {
    onSubmit: (name: string, checkout: boolean) => void
    onCancel: () => void
    pending: boolean
}) {
    const t = useT()
    const [name, setName] = useState('')
    const [checkout, setCheckout] = useState(false)

    function submit(event: FormEvent) {
        event.preventDefault()
        const trimmed = name.trim()
        if (trimmed === '') return
        onSubmit(trimmed, checkout)
    }

    return (
        <form onSubmit={submit} className="mb-1 flex flex-col gap-1 px-2">
            <input
                autoFocus
                value={name}
                onChange={(event) => setName(event.target.value)}
                onKeyDown={(event) => {
                    if (event.key === 'Escape') onCancel()
                }}
                placeholder={t('branch.namePlaceholder')}
                aria-label={t('branch.namePlaceholder')}
                disabled={pending}
                className="h-6 rounded border border-border bg-base px-1.5 text-[13px] outline-none focus-visible:ring-2 focus-visible:ring-accent/60"
            />
            <label className="flex select-none items-center gap-1.5 text-[11px] text-muted">
                <input
                    type="checkbox"
                    checked={checkout}
                    onChange={(event) => setCheckout(event.target.checked)}
                    disabled={pending}
                />
                {t('branch.checkoutAfterCreate')}
            </label>
            <div className="flex gap-1">
                <button
                    type="submit"
                    disabled={pending || name.trim() === ''}
                    className="h-6 flex-1 rounded bg-accent px-2 text-[12px] text-white outline-none focus-visible:ring-2 focus-visible:ring-accent/60 disabled:cursor-not-allowed disabled:opacity-50"
                >
                    {pending ? t('branch.creating') : t('branch.createSubmit')}
                </button>
                <button
                    type="button"
                    onClick={onCancel}
                    disabled={pending}
                    aria-label={t('branch.cancelNewBranch')}
                    title={t('branch.cancelNewBranch')}
                    className="h-6 rounded px-2 text-[12px] text-muted outline-none hover:bg-elevated focus-visible:ring-2 focus-visible:ring-accent/60"
                >
                    ✕
                </button>
            </div>
        </form>
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
                     menuOpen,
                 }: {
    name: string
    current: boolean
    currentLabel: string
    onCheckout?: () => void
    checkoutLabel: string
    onContextMenu?: (event: ReactMouseEvent) => void
    disabled: boolean
    // Whether *this* row's context menu is currently open (for aria-expanded).
    menuOpen?: boolean
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

    // A row only exposes a context menu when the caller wired one up (mergeable
    // and/or deletable). Surface that to assistive tech via aria-haspopup.
    const menuProps = onContextMenu
        ? {'aria-haspopup': 'menu' as const, 'aria-expanded': !!menuOpen}
        : {}

    // Only a branch you are not already on is worth clicking.
    if (onCheckout == null || current) {
        return (
            <div
                title={name}
                aria-current={current}
                onContextMenu={onContextMenu}
                {...menuProps}
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
            {...menuProps}
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
    const createBranch = useCreateBranch()
    const deleteBranch = useDeleteBranch()

    // The branch a right-click opened the context menu on, where to anchor it,
    // and whether it's a remote-tracking branch (which offers delete only).
    const [menu, setMenu] = useState<
        { name: string; x: number; y: number; remote: boolean } | null
    >(null)

    const [showNewBranch, setShowNewBranch] = useState(false)

    // The element that opened the menu, so focus can return to it on close —
    // and the menu's own first action button, so focus lands there on open.
    const triggerRef = useRef<HTMLElement | null>(null)
    const menuItemRef = useRef<HTMLButtonElement | null>(null)

    const {head, local, remote, tags} = useMemo(() => {
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

    // Single exit path for the menu: clears state and returns keyboard/screen
    // reader focus to whichever row opened it.
    function closeMenu() {
        setMenu(null)
        triggerRef.current?.focus()
        triggerRef.current = null
    }

    // Closing the repository leaves the menu pointing at a branch that is no
    // longer listed, so dismiss it. A detached HEAD is different: local branches
    // are still listed and still deletable, so its menu stays open — only the
    // merge action (which needs a branch to merge *into*) is hidden below.
    useEffect(() => {
        if (repositoryId == null && menu != null) closeMenu()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [repositoryId, menu])

    // Escape and scrolling both dismiss the open menu — the position is fixed, so
    // scrolling the list would otherwise leave it stranded.
    useEffect(() => {
        if (menu == null) return
        const close = () => closeMenu()
        const onKey = (event: KeyboardEvent) => {
            if (event.key === 'Escape') close()
        }
        window.addEventListener('keydown', onKey)
        window.addEventListener('scroll', close, true)
        return () => {
            window.removeEventListener('keydown', onKey)
            window.removeEventListener('scroll', close, true)
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [menu])

    // Move focus into the menu as soon as it mounts, so keyboard users land
    // directly on the first action instead of it opening silently off-screen.
    useEffect(() => {
        if (menu != null) menuItemRef.current?.focus()
    }, [menu])

    function openMenu(name: string, event: ReactMouseEvent, remote: boolean) {
        // Suppress the browser's own menu; ours takes its place.
        event.preventDefault()
        triggerRef.current = event.currentTarget as HTMLElement
        setMenu({name, x: event.clientX, y: event.clientY, remote})
    }

    function runMerge() {
        if (menu == null || repositoryId == null) return
        merge.mutate({repositoryId, source: menu.name})
        closeMenu()
    }

    // Shift-click force-deletes (skips the "not fully merged" guard). A plain
    // click still asks for confirmation either way — deleting a branch is not
    // undoable from this UI.
    function runDelete(force: boolean) {
        if (menu == null || repositoryId == null) return
        const {name, remote} = menu
        if (!window.confirm(t('branch.deleteConfirm', {name}))) return
        deleteBranch.mutate({repositoryId, name, remote, force})
        closeMenu()
    }

    function renderRefs(
        names: string[],
        markCurrent: boolean,
        checkoutable = false,
        mergeable = false,
        deletable = false,
        remote = false,
    ) {
        if (repositoryId == null) {
            return <SectionEmpty>{t('sidebar.noRepository')}</SectionEmpty>
        }
        if (refs.isPending) {
            return (
                <p className="flex items-center gap-1.5 px-2 py-0.5 text-xs text-muted">
                    <Spinner/>
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
            // Merging or deleting a branch you're on doesn't make sense, so the
            // current one is skipped either way.
            const hasMenu = (mergeable && canMerge && !current) || (deletable && !current)
            return (
                <RefItem
                    key={name}
                    name={name}
                    current={current}
                    currentLabel={t('sidebar.currentBranch')}
                    checkoutLabel={t('branch.checkout')}
                    disabled={checkout.isPending}
                    onCheckout={
                        checkoutable ? () => checkout.mutate({repositoryId, ref: name}) : undefined
                    }
                    onContextMenu={hasMenu ? (event) => openMenu(name, event, remote) : undefined}
                    menuOpen={hasMenu && menu?.name === name && menu.remote === remote}
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

                <Section
                    title={t('sidebar.branches')}
                    action={
                        repositoryId != null ? (
                            <button
                                type="button"
                                onClick={() => setShowNewBranch((v) => !v)}
                                aria-expanded={showNewBranch}
                                aria-label={t('sidebar.newBranch')}
                                title={t('sidebar.newBranch')}
                                className="flex h-4 w-4 select-none items-center justify-center rounded text-[13px] leading-none text-muted outline-none hover:bg-elevated hover:text-primary focus-visible:ring-2 focus-visible:ring-accent/60"
                            >
                                +
                            </button>
                        ) : null
                    }
                >
                    {showNewBranch ? (
                        <NewBranchForm
                            pending={createBranch.isPending}
                            onCancel={() => setShowNewBranch(false)}
                            onSubmit={(name, checkoutAfterCreate) => {
                                if (repositoryId == null) return
                                // Close only once the request is accepted: while it is
                                // pending the form stays mounted to show its disabled
                                // "Creating…" state, and on failure it stays open so the
                                // error is visible and the name can be retried.
                                createBranch.mutate(
                                    {repositoryId, name, checkout: checkoutAfterCreate},
                                    {onSuccess: () => setShowNewBranch(false)},
                                )
                            }}
                        />
                    ) : null}
                    {createBranch.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('branch.createFailed')}: {t(apiErrorKey(createBranch.error))}
                        </p>
                    ) : null}
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
                    {deleteBranch.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('branch.deleteFailed')}: {t(apiErrorKey(deleteBranch.error))}
                        </p>
                    ) : null}
                    {renderRefs(local, true, true, true, true, false)}
                </Section>

                <Section title={t('sidebar.remote')}>
                    {renderRefs(remote, false, false, false, true, true)}
                </Section>

                <Section title={t('sidebar.tags')}>{renderRefs(tags, false)}</Section>
            </nav>

            {menu != null ? (
                <>
                    {/* A full-screen catcher closes the menu on any outside click. */}
                    <div
                        className="fixed inset-0 z-40"
                        onClick={() => closeMenu()}
                        onContextMenu={(event) => {
                            event.preventDefault()
                            closeMenu()
                        }}
                    />
                    <div
                        role="menu"
                        style={{top: menu.y, left: menu.x}}
                        className="fixed z-50 min-w-max rounded-md border border-border bg-elevated py-1 text-[13px] shadow-lg"
                    >
                        {!menu.remote && canMerge ? (
                            <button
                                ref={menuItemRef}
                                type="button"
                                role="menuitem"
                                disabled={merge.isPending}
                                onClick={runMerge}
                                className="block w-full px-3 py-1 text-left text-primary/90 outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                            >
                                {t('branch.mergeInto', {source: menu.name})}
                            </button>
                        ) : null}
                        <button
                            ref={menu.remote || !canMerge ? menuItemRef : undefined}
                            type="button"
                            role="menuitem"
                            title={t('branch.forceDeleteHint')}
                            disabled={deleteBranch.isPending}
                            onClick={(event) => runDelete(event.shiftKey)}
                            className="block w-full px-3 py-1 text-left text-vcs-deleted outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('branch.delete')}
                        </button>
                    </div>
                </>
            ) : null}
        </>
    )
}
