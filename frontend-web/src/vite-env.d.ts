/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, any>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_USE_MOCK?: string
  readonly VITE_API_BASE_URL?: string
  readonly VITE_LOG_WS_URL?: string
  readonly VITE_ENABLE_PAPER_TO_CODEGEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
