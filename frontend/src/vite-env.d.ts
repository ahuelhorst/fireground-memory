/// <reference types="vite/client" />

/** Build-time environment variables (see .env.example). */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
