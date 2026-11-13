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
| `browse/` | `browse-page.tsx` (layout), `path-bar.tsx`, `result-table.tsx`, `filter-editor.tsx`, `raw-query-panel.tsx`, `inspector.tsx` |
| `lens/` | `storage-lens.tsx` (per-instance physical layout, field mapping, raw data), `native-queries.tsx` (per-path explain + force driver) |
| `mutate/` | `field-input.tsx` (typed input per DataType), `values.ts`, `insert-instance-dialog.tsx`, `edit-fields-form.tsx`, `connection-picker.tsx` |
| `graph/` | Schema graph view (`?view=schema`): `graph-model.ts` (typed node/row/edge model, no React Flow types), one builder per store view (`poly-graph.ts`, `postgres-graph.ts`, `neo4j-graph.ts`) dispatched by `store-graph.ts`, `physical-types.ts` (native type names the mapping route does not report, mirrored from the server), `mapping-key.ts` (poly element identity used for linked highlighting), `colors.ts` (one stable colour per collection), `layout.ts` (dagre → React Flow, handle ids), `nodes.tsx` / `edges.tsx`, `schema-graph.tsx` (one pane), `schema-graph-page.tsx` |

Three design points worth keeping:

- **A name may appear only once in a path**, enforced by `pathNames` / `duplicateNames` / `canAppendCollection` / `canAppendConnection` in `query/path.ts`. The server does not validate this and misbehaves differently per store (see the root `CLAUDE.md`), so the builder must not offer it: the sidebar disables entries that would repeat a name, the inspector marks a self-referential connection as untraversable, and both untrusted entry points (`query/wire.ts`, `query/url.ts`) refuse such a path. Any new way to extend a path must go through these helpers.

- **A connection step carries its target collection.** The wire format is a flat list where a `connection` segment must be followed by the `collection` it leads to; `ConnectionStep` in `query/path.ts` holds both sides so an invalid path cannot be constructed, and `toRequest()` flattens. Do not flatten the state itself.
- **Filters are clauses, not a full boolean AST.** `Filter` is a list of clauses ANDed together plus a `raw` escape hatch for DSL typed by hand. The type-aware editor needs no more; arbitrary nesting goes through `raw`. `raw` always wins over `clauses` so hand-written DSL is never silently dropped.

## Toolchain

- React 19 + React Compiler (`babel-plugin-react-compiler` via `@rolldown/plugin-babel` in `vite.config.ts`), TanStack Query, TanStack Table **v9**, Tailwind v4, shadcn/ui, lucide-react.
- TanStack Table v9 has a different API from v8: `useTable({ features, data, columns })` with `features = tableFeatures({})`, `createColumnHelper<Features, TData>()`, `ColumnDef<Features, TData, TValue>`, and `row.getAllCells()` (not `getVisibleCells()`, which needs the column-visibility feature). v8 snippets found online will not compile.
- `tsconfig.app.json` is `strict` + `noUncheckedIndexedAccess`, and `erasableSyntaxOnly` is on — so **no TS enums, no constructor parameter properties** (use `const` objects + union types, and assign fields in the constructor body).
- `baseUrl` was removed from both tsconfigs (deprecated in TS 6); `paths` alone resolves `@/*`.
- The Vite dev server proxies the API routes to `http://localhost:8080` (`vite.config.ts` → `server.proxy`). The frontend therefore uses **relative** URLs only. Do not reintroduce a host/port entry form, and do not rely on the server's open CORS.
- `eslint.config.js` relaxes `react-refresh/only-export-components` and `react-hooks/set-state-in-effect` for `src/components/ui/**` and `use-mobile.ts`, because those are shadcn-generated and regenerating would overwrite any fix. Keep app code clean of both rules instead (`theme-provider.tsx` has its hook split out into `theme-context.ts` for exactly this reason).
- No router library: the URL holds the query path (`path`) and the top-level view (`view`, omitted for the default `browse`), handled by History + `popstate` in `use-query-path.ts`.
- The schema graph uses React Flow (`@xyflow/react` v12) with `@dagrejs/dagre` for a deterministic left-to-right layout. Node components have fixed sizes that must match `nodeSize()` in `graph/layout.ts`.

## Conventions (binding)

- One typed API client in `src/api/`; components never call `fetch` directly.
- TS types are derived from the Kotlin source (see root `CLAUDE.md` "Verified API contract"), not invented. Segments: discriminated union on `type: "collection" | "connection"`. Conditions: the server takes a DSL **string** — model conditions as a typed AST/builder in the frontend and serialize to the DSL (`==`, `!=`, `<`, `>`, `in [...]`, `&&`, `||`, `!`, parens; no `<=`/`>=`).
- Exhaustive `switch` over every union, ending in `default: return assertNever(x)` (`src/lib/assert-never.ts`). A new backend variant must break the build, not fall through.
- Use shadcn/ui components before writing custom ones.
- Commit frontend changes separately from server changes.

## Known deviations from the spec

- **Delete is not implemented, because no route exists.** The spec asks for deleting an instance (with a confirmation naming the subcollection entries that go with it) and removing a connection. The server has neither: `/collection/drop` drops a whole collection, and single-instance delete and connection removal have no route or driver method (`DatabaseDriver.kt:26` carries a `// TODO: update connection`). Both would need new driver code, not just a route, so they are out of scope until that is built.
- **Only schema fields are editable.** `PostgresDriver.updateDocument` has an open TODO for dynamic data, so editing a non-schema field would write in MongoDB and Neo4j but silently drop in PostgreSQL. The edit form therefore generates inputs from `PolySchema` only.
- **Subcollection/connection count columns are not implemented.** The root `CLAUDE.md` calls for the result table to show counts that drill down on click. The HTTP API cannot produce counts: `PolyResultData.Count` exists in Kotlin but no route returns it, and `GetQuery.limit` is not exposed either — so a count per row would mean one extra query per row. The table instead shows the joined row the server actually returns, grouped by segment, and navigation happens from the sidebar. Revisit if a count route is ever added.
- **Floats are fragile end to end.** `Neo4jDriver.toPolyValue` uses `Value.asFloat()`, which throws `LossyCoercion` for a double that is not float-exact. Inserting e.g. `9.99` makes every later Neo4j read of that collection fail. Until the driver is fixed (off-limits code), prefer float-exact values in demo data.
