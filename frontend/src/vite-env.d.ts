/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend API origin; empty string means same origin (Vite dev proxy) */
  readonly VITE_API_BASE_URL?: string
  /** Dev proxy target for /api requests */
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
