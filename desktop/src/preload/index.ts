/**
 * Preload script — runs sandboxed (`sandbox: true`, `contextIsolation: true`).
 *
 * IMPORTANT: because of the sandbox this file must stay self-contained:
 * only `require('electron')` is available at runtime. Imports from ../shared
 * must be TYPE-ONLY (erased at compile time), and IPC channel names are
 * written as literals checked against the shared contract via `satisfies`.
 *
 * Handshake: the bootstrap info (backend URL + token) is fetched with a
 * synchronous IPC call. This runs before any renderer script, so
 * `window.configflow` is always defined for the frontend, and — unlike
 * `additionalArguments` — the token never shows up on an OS-visible
 * process command line.
 */

import { contextBridge, ipcRenderer } from 'electron';
import type { BootstrapInfo, IpcChannel } from '../shared/ipc';
import type { ConfigflowBridge, ConfigflowNativeBridge } from '../shared/bridge';

const bootstrap = ipcRenderer.sendSync(
  'configflow:bootstrap' satisfies IpcChannel,
) as BootstrapInfo | null;

if (bootstrap === null) {
  // Main refused the handshake (untrusted sender) — expose nothing.
  console.error('[configflow] bootstrap handshake rejected');
} else {
  const configflow: ConfigflowBridge = {
    apiBaseUrl: bootstrap.apiBaseUrl,
    token: bootstrap.token,
  };

  const configflowNative: ConfigflowNativeBridge = {
    selectDirectory: () =>
      ipcRenderer.invoke('dialog:selectDirectory' satisfies IpcChannel) as Promise<string | null>,
    openExternal: (url: string) =>
      ipcRenderer.invoke('shell:openExternal' satisfies IpcChannel, url) as Promise<void>,
    showItemInFolder: (path: string) =>
      ipcRenderer.invoke('shell:showItemInFolder' satisfies IpcChannel, path) as Promise<void>,
  };

  contextBridge.exposeInMainWorld('configflow', configflow);
  contextBridge.exposeInMainWorld('configflowNative', configflowNative);
}
