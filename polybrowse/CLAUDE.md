# polybrowse/ — polystore management UI

Vite + React 19 + TypeScript. See the root `CLAUDE.md` for the product spec (layout, storage lens, query-path concept) and the verified server API. This file covers frontend-specific state, commands and conventions.

## Commands

```
npm run dev       # Vite dev server (default port 5173)
npm run build     # tsc -b && vite build
npm run lint
npm run preview
```

The earlier prototype in `src/` (a `Home.tsx` / `query-view.tsx` / `connection-form.tsx` stack with raw `fetch` calls) was a throwaway test and has been deleted. Do not resurrect its patterns.

## Module layout

| Module | Contents |
|---|---|
| `api/` | `types.ts` (TS mirrors of the Kotlin types), `client.ts` (the only place that calls `fetch`), `hooks.ts` (TanStack Query bindings) |
| `query/` | `condition.ts` (filter clauses → condition DSL), `path.ts` (the structured query path ↔ wire format), `url.ts` (URL (de)serialization), `use-query-path.ts` (URL-backed state), `keys.ts` (query keys) |
| `schema/` | `model.ts` (read helpers over `DatabaseSchema`), `schema-sidebar.tsx` |
| `browse/` | `browse-page.tsx` (layout), `path-bar.tsx`, `result-table.tsx` |
| `lens/`, `mutate/` | not built yet (build order steps 5 and 6) |

Two design points worth keeping:

- **A connection step carries its target collection.** The wire format is a flat list where a `connection` segment must be followed by the `collection` it leads to; `ConnectionStep` in `query/path.ts` holds both sides so an invalid path cannot be constructed, and `toRequest()` flattens. Do not flatten the state itself.
- **Filters are clauses, not a full boolean AST.** `Filter` is a list of clauses ANDed together plus a `raw` escape hatch for DSL typed by hand. The type-aware editor needs no more; arbitrary nesting goes through `raw`. `raw` always wins over `clauses` so hand-written DSL is never silently dropped.

## Toolchain

- React 19 + React Compiler (`babel-plugin-react-compiler` via `@rolldown/plugin-babel` in `vite.config.ts`), TanStack Query, TanStack Table **v9**, Tailwind v4, shadcn/ui, lucide-react.
- TanStack Table v9 has a different API from v8: `useTable({ features, data, columns })` with `features = tableFeatures({})`, `createColumnHelper<Features, TData>()`, `ColumnDef<Features, TData, TValue>`, and `row.getAllCells()` (not `getVisibleCells()`, which needs the column-visibility feature). v8 snippets found online will not compile.
- `tsconfig.app.json` is `strict` + `noUncheckedIndexedAccess`, and `erasableSyntaxOnly` is on — so **no TS enums, no constructor parameter properties** (use `const` objects + union types, and assign fields in the constructor body).
- `baseUrl` was removed from both tsconfigs (deprecated in TS 6); `paths` alone resolves `@/*`.
- The Vite dev server proxies the API routes to `http://localhost:8080` (`vite.config.ts` → `server.proxy`). The frontend therefore uses **relative** URLs only. Do not reintroduce a host/port entry form, and do not rely on the server's open CORS.
- `eslint.config.js` relaxes `react-refresh/only-export-components` and `react-hooks/set-state-in-effect` for `src/components/ui/**` and `use-mobile.ts`, because those are shadcn-generated and regenerating would overwrite any fix. Keep app code clean of both rules instead (`theme-provider.tsx` has its hook split out into `theme-context.ts` for exactly this reason).
- No router library: the query path is the only URL state, handled by History + `popstate` in `use-query-path.ts`.

## Conventions (binding)

- One typed API client in `src/api/`; components never call `fetch` directly.
- TS types are derived from the Kotlin source (see root `CLAUDE.md` "Verified API contract"), not invented. Segments: discriminated union on `type: "collection" | "connection"`. Conditions: the server takes a DSL **string** — model conditions as a typed AST/builder in the frontend and serialize to the DSL (`==`, `!=`, `<`, `>`, `in [...]`, `&&`, `||`, `!`, parens; no `<=`/`>=`).
- Exhaustive `switch` over every union, ending in `default: return assertNever(x)` (`src/lib/assert-never.ts`). A new backend variant must break the build, not fall through.
- Use shadcn/ui components before writing custom ones.
- Commit frontend changes separately from server changes.

## Known deviations from the spec

- **Subcollection/connection count columns are not implemented.** The root `CLAUDE.md` calls for the result table to show counts that drill down on click. The HTTP API cannot produce counts: `PolyResultData.Count` exists in Kotlin but no route returns it, and `GetQuery.limit` is not exposed either — so a count per row would mean one extra query per row. The table instead shows the joined row the server actually returns, grouped by segment, and navigation happens from the sidebar. Revisit if a count route is ever added.
- **Floats are fragile end to end.** `Neo4jDriver.toPolyValue` uses `Value.asFloat()`, which throws `LossyCoercion` for a double that is not float-exact. Inserting e.g. `9.99` makes every later Neo4j read of that collection fail. Until the driver is fixed (off-limits code), prefer float-exact values in demo data.
