# polybrowse/ — polystore management UI

Vite + React 19 + TypeScript. See the root `CLAUDE.md` for the product spec (layout, storage lens, query-path concept) and the verified server API. This file covers frontend-specific state, commands and conventions.

## Commands

```
npm run dev       # Vite dev server (default port 5173)
npm run build     # tsc -b && vite build
npm run lint
npm run preview
```

## The existing feature code is discarded — start fresh

Per explicit owner decision, the current feature code in `src/` was **a very bad early test and is to be ignored and overridden**, not refactored or preserved:

- `src/pages/Home.tsx`, `src/components/query-view.tsx`, `src/components/app-sidebar.tsx`, `src/components/connection-form.tsx`, `src/components/theme-provider.tsx`, `src/App.tsx` contents

Do not treat its patterns as prior art (raw `fetch()` in components, hand-typed API interfaces per file, manual IP/port connection form, card-based result rendering). Delete/replace freely when building the real implementation.

**Keep as infrastructure** (not feature code): the shadcn/ui primitives in `src/components/ui/`, `src/lib/utils.ts`, `src/hooks/use-mobile.ts`, Tailwind v4 setup (`index.css`, `@tailwindcss/vite`), the React Compiler wiring in `vite.config.ts`, `components.json`, fonts.

## Current state of the toolchain

Installed and working:

- React 19, TanStack Query, Tailwind CSS v4, shadcn/ui (CLI + generated primitives), lucide-react
- React Compiler: `babel-plugin-react-compiler` via `@rolldown/plugin-babel` + `reactCompilerPreset()` in `vite.config.ts`
- `@/*` path alias → `src/*` (`tsconfig.app.json` paths + `vite.config.ts` resolve.alias)

Gaps to close as part of the real build (not as standalone cleanup):

- **No Vite dev proxy.** `vite.config.ts` has no `server.proxy`. Add one forwarding the API routes to `http://localhost:8080` (the Ktor server). The old manual IP/port connection form must not carry forward, and do not rely on the server's open CORS.
- **`"strict": true` is missing** from `tsconfig.app.json`. Enable it (the root CLAUDE.md conventions assume strict TS).
- **TanStack Table is not installed** (`@tanstack/react-table`) — add it when building the result table.
- No router library installed; the query path lives in the URL, so pick the lightest thing that serves that (even hand-rolled `URLSearchParams` state is acceptable).

## Conventions (binding)

- One typed API client in `src/api/`; components never call `fetch` directly.
- TS types are derived from the Kotlin source (see root `CLAUDE.md` "Verified API contract"), not invented. Segments: discriminated union on `type: "collection" | "connection"`. Conditions: the server takes a DSL **string** — model conditions as a typed AST/builder in the frontend and serialize to the DSL (`==`, `!=`, `<`, `>`, `in [...]`, `&&`, `||`, `!`, parens; no `<=`/`>=`).
- Exhaustive `switch` over every union with a `const _: never = x` check.
- Module split: `api/`, `query/`, `schema/`, `browse/`, `lens/`, `mutate/` — build into this structure from the start.
- Use shadcn/ui components before writing custom ones.
- Commit frontend changes separately from server changes.
