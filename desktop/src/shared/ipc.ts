/**
 * IPC contract shared between the main process and the preload script.
 *
 * NOTE: The preload script runs sandboxed (`sandbox: true`), which means it
 * cannot `require()` arbitrary project files at runtime — only `electron`
 * built-ins. Therefore the preload imports this module **type-only** and uses
 * string literals that are checked against `IpcChannel` via `satisfies`.
 * Keep the literals in `src/preload/index.ts` in sync with this file.
 */

/** Channel names owned by the main process. */
export const IPC_CHANNELS = {
  /** sync (sendSync): returns BootstrapInfo before any renderer script runs. */
  bootstrap: 'configflow:bootstrap',
  /** invoke: opens the OS directory picker, resolves the path or null. */
  selectDirectory: 'dialog:selectDirectory',
  /** invoke: opens an http/https URL in the default browser. */
  openExternal: 'shell:openExternal',
  /** invoke: reveals a file/directory in the OS file manager. */
  showItemInFolder: 'shell:showItemInFolder',
} as const;

export type IpcChannel = (typeof IPC_CHANNELS)[keyof typeof IPC_CHANNELS];

/**
 * Values handed from main to the renderer during the preload handshake.
 * The renderer talks to the Spring Boot backend directly over HTTP using
 * these; Electron IPC is reserved for native-only features.
 */
export interface BootstrapInfo {
  /** e.g. "http://127.0.0.1:53211/api/v1" */
  apiBaseUrl: string;
  /** Random per-session token, sent as `X-ConfigFlow-Token` header. */
  token: string;
}
