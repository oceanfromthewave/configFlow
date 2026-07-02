/**
 * Type declarations for the APIs the preload script exposes on `window`.
 *
 * The frontend project should reference this file (or copy of it) to get
 * typed access to `window.configflow` / `window.configflowNative`.
 */

/** Connection info for the local Spring Boot backend. */
export interface ConfigflowBridge {
  /** Base URL of the backend REST API, e.g. "http://127.0.0.1:53211/api/v1". */
  readonly apiBaseUrl: string;
  /** Session token; send as `X-ConfigFlow-Token` header on every request. */
  readonly token: string;
}

/** Native (Electron-only) capabilities exposed to the renderer. */
export interface ConfigflowNativeBridge {
  /** Opens the OS folder picker. Resolves the selected path, or null if cancelled. */
  selectDirectory(): Promise<string | null>;
  /** Opens an http/https URL in the user's default browser. */
  openExternal(url: string): Promise<void>;
  /** Reveals the given file or directory in the OS file manager. */
  showItemInFolder(path: string): Promise<void>;
}

declare global {
  interface Window {
    configflow: ConfigflowBridge;
    configflowNative: ConfigflowNativeBridge;
  }
}

export {};
