/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend API base URL used in browser dev mode (default: http://127.0.0.1:8465/api/v1) */
  readonly VITE_API_BASE_URL?: string
  /** Backend API token used in browser dev mode (default: dev-token) */
  readonly VITE_API_TOKEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
