import * as fs from 'node:fs';
import * as path from 'node:path';
import { ipcMain, shell } from 'electron';
import { IPC_CHANNELS } from '../../shared/ipc';
import type { HandlerGuard } from './index';

/** Only plain web URLs may leave the app. */
export function isSafeExternalUrl(raw: unknown): raw is string {
  if (typeof raw !== 'string') return false;
  try {
    const url = new URL(raw);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

export function registerShellHandlers(guard: HandlerGuard): void {
  ipcMain.handle(
    IPC_CHANNELS.openExternal,
    guard(async (_event, url: unknown): Promise<void> => {
      if (!isSafeExternalUrl(url)) {
        throw new Error('openExternal: only http/https URLs are allowed');
      }
      await shell.openExternal(url);
    }),
  );

  ipcMain.handle(
    IPC_CHANNELS.showItemInFolder,
    guard((_event, itemPath: unknown): void => {
      if (typeof itemPath !== 'string' || itemPath.length === 0) {
        throw new Error('showItemInFolder: path must be a non-empty string');
      }
      const resolved = path.resolve(itemPath);
      if (!fs.existsSync(resolved)) {
        throw new Error(`showItemInFolder: path does not exist: ${resolved}`);
      }
      shell.showItemInFolder(resolved);
    }),
  );
}
