import { BrowserWindow, dialog, ipcMain } from 'electron';
import { IPC_CHANNELS } from '../../shared/ipc';
import type { HandlerGuard } from './index';

export function registerDialogHandlers(guard: HandlerGuard): void {
  ipcMain.handle(
    IPC_CHANNELS.selectDirectory,
    guard(async (event): Promise<string | null> => {
      const win = BrowserWindow.fromWebContents(event.sender);
      const options = {
        properties: ['openDirectory', 'createDirectory'] as Array<
          'openDirectory' | 'createDirectory'
        >,
      };
      const result = win
        ? await dialog.showOpenDialog(win, options)
        : await dialog.showOpenDialog(options);
      if (result.canceled) return null;
      return result.filePaths[0] ?? null;
    }),
  );
}
