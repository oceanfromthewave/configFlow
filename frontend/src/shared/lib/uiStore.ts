import { create } from 'zustand'

/**
 * Pure UI state (Zustand). Server state lives in TanStack Query.
 *
 * Routing note: this is a desktop (Electron) app with no meaningful URLs,
 * deep-linking or history semantics, so navigation is a plain state switch
 * instead of react-router. Revisit if URL-addressable views are ever needed.
 */
export type AppRoute = 'welcome' | 'repository'
export type CenterTab = 'history' | 'workingTree'
export type BottomPanelTab = 'console' | 'operations' | 'log'

interface UiState {
  route: AppRoute
  setRoute: (route: AppRoute) => void

  centerTab: CenterTab
  setCenterTab: (tab: CenterTab) => void

  bottomPanelCollapsed: boolean
  toggleBottomPanel: () => void

  bottomPanelTab: BottomPanelTab
  setBottomPanelTab: (tab: BottomPanelTab) => void
}

export const useUiStore = create<UiState>()((set) => ({
  route: 'welcome',
  setRoute: (route) => set({ route }),

  centerTab: 'history',
  setCenterTab: (centerTab) => set({ centerTab }),

  bottomPanelCollapsed: true,
  toggleBottomPanel: () =>
    set((state) => ({ bottomPanelCollapsed: !state.bottomPanelCollapsed })),

  bottomPanelTab: 'console',
  setBottomPanelTab: (bottomPanelTab) => set({ bottomPanelTab }),
}))
