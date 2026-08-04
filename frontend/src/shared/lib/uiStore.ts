import { create } from 'zustand'

/**
 * Pure UI state (Zustand). Server state lives in TanStack Query.
 *
 * Routing note: this is a desktop (Electron) app with no meaningful URLs,
 * deep-linking or history semantics, so navigation is a plain state switch
 * instead of react-router. Revisit if URL-addressable views are ever needed.
 */
export type AppRoute = 'welcome' | 'repository' | 'settings'
export type CenterTab = 'history' | 'workingTree'
export type BottomPanelTab = 'console' | 'operations' | 'log'

/**
 * File whose diff the right-hand panel shows.
 *
 * Two sources feed the same panel. A working-tree file carries `staged`, which
 * picks the side being compared (the same path can differ from HEAD and from
 * the index at once); a committed file carries the `revision` it belongs to,
 * diffed against that commit's first parent.
 */
export type SelectedFile =
  | {kind: 'working'; path: string; staged: boolean}
  | {kind: 'commit'; path: string; revision: string}

/**
 * A credential prompt raised when a queued operation fails with
 * `VCS_AUTH_REQUIRED` (docs/07 §4).
 *
 * Carries the operation to retry once a credential is saved, and the remote
 * (`host`/`protocol`) the credential is for. The failure can arrive on any
 * screen, so the prompt lives here rather than in one that may not be open.
 */
export interface AuthPrompt {
  operationId: string
  host: string
  protocol: string
}

/** A pending confirmation prompt, awaiting the user's yes/no via the modal. */
export interface ConfirmRequest {
  message: string
  resolve: (confirmed: boolean) => void
}

interface UiState {
  route: AppRoute
  setRoute: (route: AppRoute) => void

  /** Repository currently open in the workspace; null on the Welcome screen. */
  currentRepositoryId: string | null
  openRepository: (repositoryId: string) => void
  closeRepository: () => void

  selectedFile: SelectedFile | null
  selectFile: (file: SelectedFile | null) => void

  centerTab: CenterTab
  setCenterTab: (tab: CenterTab) => void

  bottomPanelCollapsed: boolean
  toggleBottomPanel: () => void

  bottomPanelTab: BottomPanelTab
  setBottomPanelTab: (tab: BottomPanelTab) => void

  /**
   * Credential prompts for remotes that rejected queued operations, oldest
   * first; the modal shows the head. A queue rather than a single slot so two
   * near-simultaneous auth failures don't silently drop the earlier one.
   * Global, because an operation can fail on any screen.
   */
  authPrompts: AuthPrompt[]
  promptForAuth: (prompt: AuthPrompt) => void
  dismissAuthPrompt: () => void

  /**
   * Destructive-action confirmation, replacing `window.confirm` (which
   * Electron renders inconsistently and blocks the renderer thread). One slot
   * is enough — these are always triggered by a direct user click, so two
   * can't be in flight at once.
   */
  confirm: ConfirmRequest | null
  requestConfirm: (message: string) => Promise<boolean>
  resolveConfirm: (confirmed: boolean) => void
}

export const useUiStore = create<UiState>()((set) => ({
  route: 'welcome',
  setRoute: (route) => set({ route }),

  currentRepositoryId: null,
  // Clear the selection alongside the repository: a path from the previous one
  // would otherwise be requested against the new repository.
  openRepository: (repositoryId) =>
      set({currentRepositoryId: repositoryId, route: 'repository', selectedFile: null}),
  closeRepository: () =>
      set({currentRepositoryId: null, route: 'welcome', selectedFile: null}),

  selectedFile: null,
  selectFile: (selectedFile) => set({ selectedFile }),

  centerTab: 'history',
  setCenterTab: (centerTab) => set({ centerTab }),

  bottomPanelCollapsed: true,
  toggleBottomPanel: () =>
    set((state) => ({ bottomPanelCollapsed: !state.bottomPanelCollapsed })),

  bottomPanelTab: 'console',
  setBottomPanelTab: (bottomPanelTab) => set({ bottomPanelTab }),

  authPrompts: [],
  // Ignore a repeat of one already waiting (a redelivered SSE event); otherwise
  // append so a second failing remote queues behind the first.
  promptForAuth: (prompt) =>
    set((state) =>
      state.authPrompts.some((p) => p.operationId === prompt.operationId)
        ? state
        : { authPrompts: [...state.authPrompts, prompt] },
    ),
  dismissAuthPrompt: () =>
    set((state) => ({ authPrompts: state.authPrompts.slice(1) })),

  confirm: null,
  requestConfirm: (message) =>
    new Promise<boolean>((resolve) => set({ confirm: { message, resolve } })),
  resolveConfirm: (confirmed) =>
    set((state) => {
      state.confirm?.resolve(confirmed)
      return { confirm: null }
    }),
}))
