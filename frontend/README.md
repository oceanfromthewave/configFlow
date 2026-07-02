# ConfigFlow Frontend

React + TypeScript + Vite + Tailwind CSS v4 renderer for the ConfigFlow desktop client.
Structure follows Feature-Sliced Design (`docs/04-directory-structure.md`); UI and API
conventions follow `docs/06-ui-design.md` and `docs/07-api-design.md`.

## Scripts

| Command | Description |
|---|---|
| `npm run dev` | Vite dev server (expects backend at `http://127.0.0.1:8465/api/v1`) |
| `npm run build` | Type-check (`tsc -b`) + production build |
| `npm run typecheck` | Type-check only |
| `npm test` | Vitest (run once); `npm run test:watch` for watch mode |
| `npm run lint` | oxlint |

## Runtime configuration

The Electron preload injects `window.configflow = { apiBaseUrl, token }`.
In browser dev mode the fallbacks are `VITE_API_BASE_URL`
(default `http://127.0.0.1:8465/api/v1`) and `VITE_API_TOKEN` (default `dev-token`).
