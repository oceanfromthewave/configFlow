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
    useApplyStash,
    useCheckout,
    useCreateBranch,
    useCreateTag,
    useDeleteBranch,
    useDeleteTag,
    useDropStash,
    useMerge,
    usePopStash,
    useRefs,
    useSaveStash,
    useStashes,
} from '@/entities/repository/api/repositories'
import {buildBranchTree, type BranchTreeNode} from '@/entities/repository/lib/branchTree'
import type {RefLabel, StashEntry} from '@/entities/repository/model/types'
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

/** Inline "save stash" row shown below the STASHES header. */
function NewStashForm({
                          onSubmit,
                          onCancel,
                          pending,
                      }: {
    onSubmit: (message: string, includeUntracked: boolean) => void
    onCancel: () => void
    pending: boolean
}) {
    const t = useT()
    const [message, setMessage] = useState('')
    const [includeUntracked, setIncludeUntracked] = useState(false)

    function submit(event: FormEvent) {
        event.preventDefault()
        onSubmit(message.trim(), includeUntracked)
    }

    return (
        <form onSubmit={submit} className="mb-1 flex flex-col gap-1 px-2">
            <input
                autoFocus
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                onKeyDown={(event) => {
                    if (event.key === 'Escape') onCancel()
                }}
                placeholder={t('stash.messagePlaceholder')}
                aria-label={t('stash.messagePlaceholder')}
                disabled={pending}
                className="h-6 rounded border border-border bg-base px-1.5 text-[13px] outline-none focus-visible:ring-2 focus-visible:ring-accent/60"
            />
            <label className="flex select-none items-center gap-1.5 text-[11px] text-muted">
                <input
                    type="checkbox"
                    checked={includeUntracked}
                    onChange={(event) => setIncludeUntracked(event.target.checked)}
                    disabled={pending}
                />
                {t('stash.includeUntracked')}
            </label>
            <div className="flex gap-1">
                <button
                    type="submit"
                    disabled={pending}
                    className="h-6 flex-1 rounded bg-accent px-2 text-[12px] text-white outline-none focus-visible:ring-2 focus-visible:ring-accent/60 disabled:cursor-not-allowed disabled:opacity-50"
                >
                    {pending ? t('stash.saving') : t('stash.saveSubmit')}
                </button>
                <button
                    type="button"
                    onClick={onCancel}
                    disabled={pending}
                    aria-label={t('stash.cancelNewStash')}
                    title={t('stash.cancelNewStash')}
                    className="h-6 rounded px-2 text-[12px] text-muted outline-none hover:bg-elevated focus-visible:ring-2 focus-visible:ring-accent/60"
                >
                    ✕
                </button>
            </div>
        </form>
    )
}

/** Inline "create tag" row shown below the TAGS header. */
function NewTagForm({
                        onSubmit,
                        onCancel,
                        pending,
                    }: {
    onSubmit: (name: string, message: string) => void
    onCancel: () => void
    pending: boolean
}) {
    const t = useT()
    const [name, setName] = useState('')
    const [message, setMessage] = useState('')

    function submit(event: FormEvent) {
        event.preventDefault()
        const trimmed = name.trim()
        if (trimmed === '') return
        onSubmit(trimmed, message.trim())
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
                placeholder={t('tag.namePlaceholder')}
                aria-label={t('tag.namePlaceholder')}
                disabled={pending}
                className="h-6 rounded border border-border bg-base px-1.5 text-[13px] outline-none focus-visible:ring-2 focus-visible:ring-accent/60"
            />
            <input
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                onKeyDown={(event) => {
                    if (event.key === 'Escape') onCancel()
                }}
                placeholder={t('tag.messagePlaceholder')}
                aria-label={t('tag.messagePlaceholder')}
                disabled={pending}
                className="h-6 rounded border border-border bg-base px-1.5 text-[13px] outline-none focus-visible:ring-2 focus-visible:ring-accent/60"
            />
            <div className="flex gap-1">
                <button
                    type="submit"
                    disabled={pending || name.trim() === ''}
                    className="h-6 flex-1 rounded bg-accent px-2 text-[12px] text-white outline-none focus-visible:ring-2 focus-visible:ring-accent/60 disabled:cursor-not-allowed disabled:opacity-50"
                >
                    {pending ? t('tag.creating') : t('tag.createSubmit')}
                </button>
                <button
                    type="button"
                    onClick={onCancel}
                    disabled={pending}
                    aria-label={t('tag.cancelNewTag')}
                    title={t('tag.cancelNewTag')}
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
                     label = name,
                     current,
                     currentLabel,
                     onCheckout,
                     checkoutLabel,
                     onContextMenu,
                     disabled,
                     menuOpen,
                 }: {
    name: string
    // Text shown in the row; defaults to the full ref name. A tree leaf passes
    // just its last path segment while `name` stays the full ref for actions.
    label?: string
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
            <span className="min-w-0 truncate">{label}</span>
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

/** One row of the STASHES section; clicking it applies the stash. */
function StashItem({
                        entry,
                        onApply,
                        onContextMenu,
                        disabled,
                        menuOpen,
                    }: {
    entry: StashEntry
    onApply: () => void
    onContextMenu: (event: ReactMouseEvent) => void
    disabled: boolean
    menuOpen: boolean
}) {
    const t = useT()
    // A stash may be saved without a message; fall back to its position so the
    // row is never blank.
    const label = entry.message.trim() !== '' ? entry.message : `stash@{${entry.index}}`
    return (
        <button
            type="button"
            title={`${t('stash.apply')}: ${label}`}
            aria-label={`${t('stash.apply')}: ${label}`}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            disabled={disabled}
            onClick={onApply}
            onContextMenu={onContextMenu}
            className="flex h-6 w-full select-none items-center gap-1.5 rounded px-2 text-left text-[13px] text-primary/90 outline-none hover:bg-elevated focus-visible:ring-2 focus-visible:ring-accent/60 disabled:cursor-not-allowed disabled:opacity-50"
        >
            <span className="min-w-0 truncate">{label}</span>
        </button>
    )
}

/**
 * Left sidebar (docs/06 §1): WORKSPACE / BRANCHES / TAGS / STASHES.
 * SVN LOCKS appears later, gated by Capability.
 */
export function Sidebar() {
    const t = useT()
    const repositoryId = useUiStore((s) => s.currentRepositoryId)
    const refs = useRefs(repositoryId)
    const checkout = useCheckout()
    const merge = useMerge()
    const createBranch = useCreateBranch()
    const deleteBranch = useDeleteBranch()
    const createTag = useCreateTag()
    const deleteTag = useDeleteTag()

    const stashes = useStashes(repositoryId)
    const saveStash = useSaveStash()
    const applyStash = useApplyStash()
    const popStash = usePopStash()
    const dropStash = useDropStash()

    // The branch a right-click opened the context menu on, where to anchor it,
    // and whether it's a remote-tracking branch (which offers delete only).
    const [menu, setMenu] = useState<
        { name: string; x: number; y: number; remote: boolean } | null
    >(null)

    const [showNewBranch, setShowNewBranch] = useState(false)

    // Folder paths (prefixed "local:"/"remote:" so the two trees don't collide)
    // that are currently collapsed; everything else renders expanded.
    const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(new Set())
    function toggleFolder(key: string) {
        setCollapsedFolders((prev) => {
            const next = new Set(prev)
            if (next.has(key)) next.delete(key)
            else next.add(key)
            return next
        })
    }

    // The element that opened the menu, so focus can return to it on close —
    // and the menu's own first action button, so focus lands there on open.
    const triggerRef = useRef<HTMLElement | null>(null)
    const menuItemRef = useRef<HTMLButtonElement | null>(null)

    // The stash a right-click opened the context menu on, and where to anchor it.
    // A separate menu from the branch one above: different rows, different actions.
    const [stashMenu, setStashMenu] = useState<
        { entry: StashEntry; x: number; y: number } | null
    >(null)
    const [showNewStash, setShowNewStash] = useState(false)
    const stashTriggerRef = useRef<HTMLElement | null>(null)
    const stashMenuItemRef = useRef<HTMLButtonElement | null>(null)

    // The tag a right-click opened the context menu on, and where to anchor it.
    // A separate menu from the branch one: tags offer delete only, no merge.
    const [tagMenu, setTagMenu] = useState<{ name: string; x: number; y: number } | null>(null)
    const [showNewTag, setShowNewTag] = useState(false)
    const tagTriggerRef = useRef<HTMLElement | null>(null)
    const tagMenuItemRef = useRef<HTMLButtonElement | null>(null)

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

    function closeStashMenu() {
        setStashMenu(null)
        stashTriggerRef.current?.focus()
        stashTriggerRef.current = null
    }

    useEffect(() => {
        if (repositoryId == null && stashMenu != null) closeStashMenu()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [repositoryId, stashMenu])

    useEffect(() => {
        if (stashMenu == null) return
        const close = () => closeStashMenu()
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
    }, [stashMenu])

    useEffect(() => {
        if (stashMenu != null) stashMenuItemRef.current?.focus()
    }, [stashMenu])

    function openStashMenu(entry: StashEntry, event: ReactMouseEvent) {
        event.preventDefault()
        stashTriggerRef.current = event.currentTarget as HTMLElement
        setStashMenu({entry, x: event.clientX, y: event.clientY})
    }

    function closeTagMenu() {
        setTagMenu(null)
        tagTriggerRef.current?.focus()
        tagTriggerRef.current = null
    }

    useEffect(() => {
        if (repositoryId == null && tagMenu != null) closeTagMenu()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [repositoryId, tagMenu])

    useEffect(() => {
        if (tagMenu == null) return
        const close = () => closeTagMenu()
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
    }, [tagMenu])

    useEffect(() => {
        if (tagMenu != null) tagMenuItemRef.current?.focus()
    }, [tagMenu])

    function openTagMenu(name: string, event: ReactMouseEvent) {
        event.preventDefault()
        tagTriggerRef.current = event.currentTarget as HTMLElement
        setTagMenu({name, x: event.clientX, y: event.clientY})
    }

    function runApply(entry: StashEntry) {
        if (repositoryId == null) return
        applyStash.mutate({repositoryId, index: entry.index})
    }

    function runPop() {
        if (stashMenu == null || repositoryId == null) return
        popStash.mutate({repositoryId, index: stashMenu.entry.index})
        closeStashMenu()
    }

    function runDropStash() {
        if (stashMenu == null || repositoryId == null) return
        const {entry} = stashMenu
        const label = entry.message.trim() !== '' ? entry.message : `stash@{${entry.index}}`
        if (!window.confirm(t('stash.dropConfirm', {message: label}))) return
        dropStash.mutate({repositoryId, index: entry.index})
        closeStashMenu()
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

    function runDeleteTag() {
        if (tagMenu == null || repositoryId == null) return
        const {name} = tagMenu
        if (!window.confirm(t('tag.deleteConfirm', {name}))) return
        deleteTag.mutate({repositoryId, name})
        closeTagMenu()
    }

    function renderRefs(
        names: string[],
        markCurrent: boolean,
        checkoutable = false,
        mergeable = false,
        deletable = false,
        remote = false,
        // Slash-delimited names (branches) fold into a collapsible folder tree;
        // tags stay a flat list.
        grouped = false,
        // Branches and tags open different context menus (merge+delete vs.
        // delete-only) backed by different state, so the default here only
        // covers the branch/remote-branch case; tags pass their own.
        onContext: (name: string, event: ReactMouseEvent) => void = (name, event) =>
            openMenu(name, event, remote),
        isMenuOpen: (name: string) => boolean = (name) =>
            menu?.name === name && menu.remote === remote,
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

        function renderLeaf(name: string, label: string, depth: number) {
            const current = markCurrent && name === head
            // Merging or deleting a branch you're on doesn't make sense, so the
            // current one is skipped either way.
            const hasMenu = (mergeable && canMerge && !current) || (deletable && !current)
            return (
                <div key={name} style={depth > 0 ? {paddingLeft: depth * 14} : undefined}>
                    <RefItem
                        name={name}
                        label={label}
                        current={current}
                        currentLabel={t('sidebar.currentBranch')}
                        checkoutLabel={t('branch.checkout')}
                        disabled={checkout.isPending}
                        onCheckout={
                            checkoutable ? () => checkout.mutate({repositoryId, ref: name}) : undefined
                        }
                        onContextMenu={hasMenu ? (event) => onContext(name, event) : undefined}
                        menuOpen={hasMenu && isMenuOpen(name)}
                    />
                </div>
            )
        }

        if (!grouped) {
            return names.map((name) => renderLeaf(name, name, 0))
        }

        const sectionKey = remote ? 'remote' : 'local'

        function renderTree(nodes: BranchTreeNode[], depth: number): ReactNode {
            return nodes.map((node) => {
                if (node.kind === 'leaf') return renderLeaf(node.name, node.label, depth)
                const key = `${sectionKey}:${node.path}`
                const collapsed = collapsedFolders.has(key)
                return (
                    <div key={key}>
                        <button
                            type="button"
                            onClick={() => toggleFolder(key)}
                            aria-expanded={!collapsed}
                            style={{paddingLeft: depth * 14}}
                            className="flex h-6 w-full select-none items-center gap-1 rounded px-2 text-left text-[13px] text-muted outline-none hover:bg-elevated hover:text-primary focus-visible:ring-2 focus-visible:ring-accent/60"
                        >
                            <span className="w-3 shrink-0 text-[10px]">{collapsed ? '▸' : '▾'}</span>
                            <span className="min-w-0 truncate">{node.label}</span>
                        </button>
                        {collapsed ? null : renderTree(node.children, depth + 1)}
                    </div>
                )
            })
        }

        return renderTree(buildBranchTree(names), 0)
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
                    {renderRefs(local, true, true, true, true, false, true)}
                </Section>

                <Section title={t('sidebar.remote')}>
                    {renderRefs(remote, false, false, false, true, true, true)}
                </Section>

                <Section
                    title={t('sidebar.tags')}
                    action={
                        repositoryId != null ? (
                            <button
                                type="button"
                                onClick={() => setShowNewTag((v) => !v)}
                                aria-expanded={showNewTag}
                                aria-label={t('sidebar.newTag')}
                                title={t('sidebar.newTag')}
                                className="flex h-4 w-4 select-none items-center justify-center rounded text-[13px] leading-none text-muted outline-none hover:bg-elevated hover:text-primary focus-visible:ring-2 focus-visible:ring-accent/60"
                            >
                                +
                            </button>
                        ) : null
                    }
                >
                    {showNewTag ? (
                        <NewTagForm
                            pending={createTag.isPending}
                            onCancel={() => setShowNewTag(false)}
                            onSubmit={(name, message) => {
                                if (repositoryId == null) return
                                createTag.mutate(
                                    {repositoryId, name, message: message === '' ? undefined : message},
                                    {onSuccess: () => setShowNewTag(false)},
                                )
                            }}
                        />
                    ) : null}
                    {createTag.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('tag.createFailed')}: {t(apiErrorKey(createTag.error))}
                        </p>
                    ) : null}
                    {deleteTag.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('tag.deleteFailed')}: {t(apiErrorKey(deleteTag.error))}
                        </p>
                    ) : null}
                    {renderRefs(tags, false, false, false, true, false, false, openTagMenu, (name) => tagMenu?.name === name)}
                </Section>

                <Section
                    title={t('sidebar.stashes')}
                    action={
                        repositoryId != null ? (
                            <button
                                type="button"
                                onClick={() => setShowNewStash((v) => !v)}
                                aria-expanded={showNewStash}
                                aria-label={t('sidebar.newStash')}
                                title={t('sidebar.newStash')}
                                className="flex h-4 w-4 select-none items-center justify-center rounded text-[13px] leading-none text-muted outline-none hover:bg-elevated hover:text-primary focus-visible:ring-2 focus-visible:ring-accent/60"
                            >
                                +
                            </button>
                        ) : null
                    }
                >
                    {showNewStash ? (
                        <NewStashForm
                            pending={saveStash.isPending}
                            onCancel={() => setShowNewStash(false)}
                            onSubmit={(message, includeUntracked) => {
                                if (repositoryId == null) return
                                saveStash.mutate(
                                    {repositoryId, message: message === '' ? undefined : message, includeUntracked},
                                    {onSuccess: () => setShowNewStash(false)},
                                )
                            }}
                        />
                    ) : null}
                    {saveStash.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('stash.saveFailed')}: {t(apiErrorKey(saveStash.error))}
                        </p>
                    ) : null}
                    {applyStash.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('stash.applyFailed')}: {t(apiErrorKey(applyStash.error))}
                        </p>
                    ) : null}
                    {popStash.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('stash.popFailed')}: {t(apiErrorKey(popStash.error))}
                        </p>
                    ) : null}
                    {dropStash.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('stash.dropFailed')}: {t(apiErrorKey(dropStash.error))}
                        </p>
                    ) : null}
                    {repositoryId == null ? (
                        <SectionEmpty>{t('sidebar.noRepository')}</SectionEmpty>
                    ) : stashes.isPending ? (
                        <p className="flex items-center gap-1.5 px-2 py-0.5 text-xs text-muted">
                            <Spinner/>
                            {t('stash.loading')}
                        </p>
                    ) : stashes.isError ? (
                        <p className="px-2 py-0.5 text-xs text-vcs-deleted">
                            {t('stash.loadFailed')}
                        </p>
                    ) : (stashes.data ?? []).length === 0 ? (
                        <SectionEmpty>{t('sidebar.emptySection')}</SectionEmpty>
                    ) : (
                        (stashes.data ?? []).map((entry) => (
                            <StashItem
                                key={entry.index}
                                entry={entry}
                                disabled={applyStash.isPending}
                                onApply={() => runApply(entry)}
                                onContextMenu={(event) => openStashMenu(entry, event)}
                                menuOpen={stashMenu?.entry.index === entry.index}
                            />
                        ))
                    )}
                </Section>
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

            {stashMenu != null ? (
                <>
                    {/* A full-screen catcher closes the menu on any outside click. */}
                    <div
                        className="fixed inset-0 z-40"
                        onClick={() => closeStashMenu()}
                        onContextMenu={(event) => {
                            event.preventDefault()
                            closeStashMenu()
                        }}
                    />
                    <div
                        role="menu"
                        style={{top: stashMenu.y, left: stashMenu.x}}
                        className="fixed z-50 min-w-max rounded-md border border-border bg-elevated py-1 text-[13px] shadow-lg"
                    >
                        <button
                            ref={stashMenuItemRef}
                            type="button"
                            role="menuitem"
                            disabled={applyStash.isPending}
                            onClick={() => {
                                runApply(stashMenu.entry)
                                closeStashMenu()
                            }}
                            className="block w-full px-3 py-1 text-left text-primary/90 outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('stash.apply')}
                        </button>
                        <button
                            type="button"
                            role="menuitem"
                            disabled={popStash.isPending}
                            onClick={runPop}
                            className="block w-full px-3 py-1 text-left text-primary/90 outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('stash.pop')}
                        </button>
                        <button
                            type="button"
                            role="menuitem"
                            disabled={dropStash.isPending}
                            onClick={runDropStash}
                            className="block w-full px-3 py-1 text-left text-vcs-deleted outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('stash.drop')}
                        </button>
                    </div>
                </>
            ) : null}

            {tagMenu != null ? (
                <>
                    {/* A full-screen catcher closes the menu on any outside click. */}
                    <div
                        className="fixed inset-0 z-40"
                        onClick={() => closeTagMenu()}
                        onContextMenu={(event) => {
                            event.preventDefault()
                            closeTagMenu()
                        }}
                    />
                    <div
                        role="menu"
                        style={{top: tagMenu.y, left: tagMenu.x}}
                        className="fixed z-50 min-w-max rounded-md border border-border bg-elevated py-1 text-[13px] shadow-lg"
                    >
                        <button
                            ref={tagMenuItemRef}
                            type="button"
                            role="menuitem"
                            disabled={deleteTag.isPending}
                            onClick={runDeleteTag}
                            className="block w-full px-3 py-1 text-left text-vcs-deleted outline-none hover:bg-base focus-visible:bg-base disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {t('tag.delete')}
                        </button>
                    </div>
                </>
            ) : null}
        </>
    )
}
