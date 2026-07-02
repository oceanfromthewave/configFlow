/**
 * Registration point for all native IPC handlers.
 * Per architecture docs (03 §1): Electron IPC is used ONLY for native
 * features (dialogs, shell integration). All VCS/domain traffic goes over
 * HTTP directly from the renderer to the backend.
 */

import { ipcMain, type IpcMainEvent, type IpcMainInvokeEvent, type WebContents } from 'electron';
import { IPC_CHANNELS, type BootstrapInfo } from '../../shared/ipc';
import { registerDialogHandlers } from './dialog';
import { registerShellHandlers } from './shell';

export interface IpcContext {
  bootstrap: BootstrapInfo;
  /** Only IPC from this WebContents is honored. */
  isTrustedSender(sender: WebContents): boolean;
}

export function registerIpcHandlers(ctx: IpcContext): void {
  // Synchronous handshake: the preload calls sendSync before any renderer
  // script runs, so window.configflow is populated with no race and the
  // token never appears on a process command line (unlike additionalArguments).
  ipcMain.on(IPC_CHANNELS.bootstrap, (event: IpcMainEvent) => {
    if (!ctx.isTrustedSender(event.sender) || event.senderFrame !== event.sender.mainFrame) {
      event.returnValue = null;
      return;
    }
    event.returnValue = ctx.bootstrap;
  });

  const guard =
    <A extends unknown[], R>(handler: (event: IpcMainInvokeEvent, ...args: A) => R) =>
    (event: IpcMainInvokeEvent, ...args: A): R => {
      if (!ctx.isTrustedSender(event.sender)) {
        throw new Error('IPC call from untrusted sender rejected');
      }
      return handler(event, ...args);
    };

  registerDialogHandlers(guard);
  registerShellHandlers(guard);
}

/** Wraps an invoke-handler with the trusted-sender check. */
export type HandlerGuard = <A extends unknown[], R>(
  handler: (event: IpcMainInvokeEvent, ...args: A) => R,
) => (event: IpcMainInvokeEvent, ...args: A) => R;
