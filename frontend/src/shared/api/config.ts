/**
 * Runtime configuration resolution.
 *
 * In the packaged app the Electron preload injects `window.configflow`
 * (dynamic port + session token, see docs/07 §1). In plain browser dev
 * we fall back to Vite env vars with sensible defaults.
 */

export interface ConfigFlowRuntime {
  apiBaseUrl: string
  token: string
}

declare global {
  interface Window {
    /** Injected by the Electron preload script. Absent in browser dev. */
    configflow?: ConfigFlowRuntime
  }
}

export const DEFAULT_API_BASE_URL = 'http://127.0.0.1:8465/api/v1'
export const DEFAULT_API_TOKEN = 'dev-token'

export function getRuntimeConfig(): ConfigFlowRuntime {
  if (typeof window !== 'undefined' && window.configflow) {
    return window.configflow
  }
  return {
    apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL,
    token: import.meta.env.VITE_API_TOKEN ?? DEFAULT_API_TOKEN,
  }
}
