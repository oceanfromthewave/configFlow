/**
 * Native capabilities the Electron preload injects on `window`.
 *
 * Absent in a plain browser — which is how the app runs during development at
 * :5173 — so every caller has to cope with them being missing rather than
 * assume Electron.
 */
export interface NativeBridge {
  /** OS folder picker. Resolves the chosen path, or null if the user cancelled. */
  selectDirectory(): Promise<string | null>
  openExternal(url: string): Promise<void>
  showItemInFolder(path: string): Promise<void>
}

declare global {
  interface Window {
    configflowNative?: NativeBridge
  }
}

/** The bridge, or null when running outside Electron. */
export function nativeBridge(): NativeBridge | null {
  if (typeof window === 'undefined') return null
  return window.configflowNative ?? null
}

/** True when the OS folder picker can be opened. */
export function canPickDirectory(): boolean {
  return typeof nativeBridge()?.selectDirectory === 'function'
}

/**
 * Opens the OS folder picker.
 *
 * @returns the selected absolute path, or null if cancelled or unavailable
 */
export async function pickDirectory(): Promise<string | null> {
  const bridge = nativeBridge()
  if (!bridge) return null
  return bridge.selectDirectory()
}
