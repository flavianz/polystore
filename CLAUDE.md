# Polystore + polybrowse

This repository (one single git repo) contains a polystore (`server/`), its data management interface **polybrowse** (`polybrowse/`), and Python benchmark analysis tooling (`analysis/`). It is part of a Swiss Maturarbeit ("Poly Speicher Optimierung").

Everything in this file has been verified against the code (2026-11-13). File paths are cited so claims can be re-checked.

## The polystore (`server/`)

The polystore is a Kotlin layer that exposes **one unified abstract data model** over three backing stores:

- **PostgreSQL**
- **MongoDB** (sync driver)
- **Neo4j** (Bolt)

**Data is fully replicated**: every write fans out to all three stores via `DriverManager.execute` (`server/src/main/kotlin/driver/DriverManager.kt:24-28`). Reads go to exactly **one** store, chosen per query by ML-based routing (`DriverManager.chooseDriverModel`, backed by `RegressionModel.calculateFastestDriverRegression`) — there is no function named `pickBestDriver`. A heuristic alternative `chooseDriverSimple` exists but is only used by the benchmark harness, not the production read path.

This is a *polystore*: one logical model mapped onto several stores. It is **not** polyglot persistence, where different parts of an application use different databases independently. Keep this distinction correct in code, comments and UI copy.

The stack is Kotlin, Ktor as the HTTP server, Gradle with the Kotlin DSL, and Shadow / `buildFatJar` for packaging.

### Key abstractions

| Area | Names |
|---|---|
| Drivers | `DatabaseDriver` (interface), `PostgresDriver`, `MongoDriver`, `Neo4jDriver`, `DriverManager`, `DatabaseManager` |
| Schema and data | `PolySchema` (= `Map<String, DataType>`), `PolyData` (= `Map<String, Any?>`), `DataType` (enum). There is **no `PolyValue` type** — field values are raw JVM types. |
| Queries | `GetQuery` (a `QueryPath` + optional limit), `QueryPath` (list of `QuerySegment`s), `Condition` (sealed filter hierarchy), `ConditionParser` (string DSL → `Condition`). There is **no `InstancePath` or `PolyQL` type.** |
| Routing | `DriverManager.chooseDriverModel` (production), `chooseDriverSimple` (benchmark-only), `RegressionModel` |
| Benchmarking | `benchmark/` package, `BenchEnvironmentRegression`. `form_id` appears only in `analysis/` Python CSVs, not in the Kotlin code. |

### Physical naming conventions (verified)

| Name | Store | Meaning |
|---|---|---|
| `ps_col_<name>` | Postgres table / Neo4j node label | A poly collection (`PostgresDriver.kt:36-37`, `Neo4jDriver.kt:463`) |
| `ps_con_<name>` | Postgres join table | A connection (two FK columns). In Neo4j the relationship type is the bare connection name, no prefix. |
| `ps_cfk_<collectionName>` | Postgres column | FK column in a connection join table pointing to one side (`PostgresDriver.kt:103-104`) |
| `ps_parent_fk` | Postgres column | FK from a subcollection table to its parent's `_id` (`PostgresDriver.kt:42-46`) |
| `ps_parent_of` | Neo4j relationship type | Directed parent → child edge for subcollections (`Neo4jDriver.kt:100-106`) |
| `ps_config_collections` / `ps_config_connections` | all stores | Metadata tables/collections/labels holding the registered schema |
| `_sub_<name>` | Mongo field | Embedded array holding a subcollection's documents (`MongoDriver.kt:72`) |
| `_con_<name>` | Mongo field | Embedded array of connection entries, each `{_rel: <connection data>, _doc: <other side's document>}`, duplicated into **both** sides' documents (`MongoDriver.kt:181-188`) |

There are **no** `ps_f_` or `ps_sub_` prefixes. Poly fields map to plain Postgres columns, plain Mongo document keys, and plain Neo4j node properties, all under the field's own name. Postgres additionally keeps non-schema ("dynamic") fields in a `_dynamic_data JSONB` column.

### Data model concepts

- A **collection** contains instances with typed fields (`DataType`). Every instance has a server-generated `_id` (`java.util.UUID`).
- A **subcollection** is a collection with a `parentCollection`; each of its instances is owned by one parent instance. Subcollections can nest further.
- A **connection** is a named many-to-many reference between two collections, optionally carrying its own typed data (`connectionDataSchema`).
  - **Directionality**: Neo4j stores one directed edge `(collection1)-[:name]->(collection2)`; Postgres stores an undirected join-table row; Mongo denormalizes the entry into both documents.
  - **Incoming connections are queryable in all three stores.** Direction is implicit in path order: whichever collection appears before the connection segment is the starting side, and the drivers traverse the stored edge in either direction (`Neo4jDriver.kt:370-377`, `PostgresDriver.kt:449-472`). There is no explicit direction flag in the query model.

### Off-limits

- **Do not change the existing query semantics, driver logic, routing logic, or any benchmark / ML code.** The thesis results are based on this code. See `server/CLAUDE.md` for the concrete file list.
- **Server changes must be additive**: new routes, optional flags on existing routes, and serialization of information that already exists internally.
- **Propose server changes in a plan and get approval before implementing them.**
- **Commit server changes separately from frontend changes.**

## Verified API contract

Server: Ktor/Netty on **port 8080** (hardcoded, `server/src/main/kotlin/server/Server.kt:28`). Start with `./gradlew run` from `server/`. CORS is open (`anyHost()`, GET/POST) for development. DB connections are hardcoded in `server/src/main/kotlin/Main.kt:16-35` — no config files or env vars.

### All routes (`Server.kt:38-304`)

| Method | Path | Request body | Response |
|---|---|---|---|
| POST | `/collection/create` | `{name, fields: [{name, type}], parentCollection?}` | 201 text |
| POST | `/collection/drop` | `{name, recursive}` | 201 text |
| POST | `/connection/create` | `{name, collection1Name, collection2Name, fields: [{name, type}]}` | 201 text |
| POST | `/connection/drop` | `{name}` | 201 text |
| POST | `/document/insert` | `{collection, fields: {<field>: <json value>}, parentDocUuid}` (`parentDocUuid` nullable but the key must be present) | 201 text `"Inserted document with UUID <uuid>"` |
| POST | `/connection/insert` | `{connection, collection1Name, collection1Uuid, collection2Name, collection2Uuid, fields}` | 201 text |
| POST | `/query/get` | see below | JSON (see below) |
| POST | `/query/explain` | same as `/query/get` | native query per driver + routing decision, no execution (see below) |
| POST | `/instance/raw` | `{collection, id}` | raw per-store representation of one instance (see below) |
| POST | `/document/update` | `{collection, uuid, fields}` | 200 text; updates scalar fields in all stores |
| GET | `/schema/mapping/{collection}` | — | poly field → physical location per store (see below) |
| GET | `/collections/list` | — | `[CollectionModel]` |
| GET | `/connections/list` | — | `[ConnectionModel]` |
| GET | `/schema` | — | `DatabaseSchema` (see below) |
| GET | `/version` | — | `"0.0.1"` |

There is **no** `PUT`/`DELETE` (all mutations are POST) and no delete-single-instance or remove-single-connection route. Address one instance via a `_id == <uuid>` condition in `/query/get`.

### `POST /query/get` request

DTOs: `QueryRequest` / `RequestQuerySegment` (`server/src/main/kotlin/server/RequestDefinitions.kt:102-113`). **There is no kotlinx polymorphic discriminator anywhere** — segments are a flat shape discriminated by the plain string field `type`, and filters are a **DSL string**, not JSON objects:

```json
{ "path": [ { "name": "...", "type": "collection" | "connection", "condition": "<DSL string or null>", "only": ["field", ...] | null } ], "forceDriver": "postgres" | "mongo" | "neo4j" | null }
```

All four keys are required on every segment (`condition`/`only` may be `null`). `forceDriver` is optional; when present, routing is skipped and the named driver executes the query (400 on an unknown name, 500 if that driver is not active). Rules enforced in `Server.kt:169-208`:

- The first segment must be a `collection`.
- A `connection` segment must be immediately followed by a `collection` segment (the target side); the pair is merged into one `QuerySegment.Connection` server-side.
- A subcollection is traversed by simply appending its collection segment after the parent's.
- `only` projects the returned fields of that segment.

**Condition DSL** (parsed by `server/src/main/kotlin/server/ConditionParser.kt` into the sealed `Condition` hierarchy, `query/Condition.kt`):

- Comparisons: `field == value`, `field != value`, `field < number`, `field > number`, `field in [v1, v2, ...]`. **`<=` and `>=` are tokenized but rejected by the parser.**
- Logic: `&&`, `||`, `!`, parentheses. Precedence: `!` > `&&` > `||`.
- Values: `'string'` or `"string"`, `true`/`false`/`null`, ints, floats, and **bare 36-char UUID literals** (unquoted).
- The 7 `Condition` leaf variants: `Comparison.Equals`, `Comparison.GreaterThan`, `Comparison.LessThan`, `Logic.And`, `Logic.Or`, `Not`, `In`.

Example — users aged 30–50, into their `orders` subcollection, then across the `ordered` connection to `products`:

```json
{
  "path": [
    { "name": "users",    "type": "collection", "condition": "age > 30 && age < 50", "only": null },
    { "name": "orders",   "type": "collection", "condition": "status == 'open'",     "only": ["status"] },
    { "name": "ordered",  "type": "connection", "condition": "quantity > 1",          "only": null },
    { "name": "products", "type": "collection", "condition": null,                    "only": ["name", "price"] }
  ]
}
```

### `POST /query/get` response

Built by hand in `query/QueryResult.kt` (`buildJsonObject`, not reflection). Note: the handler responds with the JSON as a `String`, so the body is JSON text (possibly with a text content type) — parse the body as JSON regardless.

```json
{
  "duration": { "queryBuilding": "1.2ms", "queryExecuting": "3.4ms", "processing": "0.5ms" },
  "env": { "driver": "postgres", "executedQueries": ["SELECT ..."] },
  "data": {
    "type": "documents",
    "data": [
      {
        "users":    { "_id": "6f1c...-...", "age": 42, "name": "..." },
        "orders":   { "_id": "....", "status": "open" },
        "ordered":  { "quantity": 2 },
        "products": { "name": "...", "price": 9.99 }
      }
    ]
  }
}
```

- One result row per matched path combination; keys are the segment names, values the (projected) fields of that segment's instance.
- `env.driver` is `"postgres" | "mongo" | "neo4j"` — the store the router picked. `env.executedQueries` contains the native query/queries actually run.
- Durations are Kotlin `Duration.toString()` strings.
- Field value serialization: Int/Float/Boolean/String as JSON primitives, `UUID` as string, `null` as JSON null.
- The alternate `data` variant is `{ "type": "count", "count": n }` (`PolyResultData.Count`), currently not producible via the HTTP API.

### Identity

Instance IDs are server-generated `java.util.UUID`s, exposed as the `_id` field and as strings in JSON. There is no `InstancePath` type; a single instance is addressed by a query path ending in `condition: "_id == <uuid>"`.

### Inspection routes (added for polybrowse; read-only, routing/driver logic untouched)

- **`POST /query/explain`** — same body as `/query/get` (`forceDriver` ignored). Returns `{"routedDriver": "postgres"|..., "queries": {"postgres": [sql], "mongo": [desc], "neo4j": [cypher]}}` without executing anything. For Mongo paths of 3+ segments there is no single native query; the entry says so (the driver runs sequential finds joined in memory). On a routing failure `routedDriver` is null and `routingError` carries the message.
- **`POST /instance/raw`** — body `{collection, id}`. Returns `{"postgres": {"tables": {"ps_col_x": [rows], "ps_con_y": [rows], ...}}, "mongo": {"document": {...}, "parentDocument": {...}|null}, "neo4j": {"node": {labels, properties}, "relationships": [{type, direction, properties, otherLabels, otherId}]}}`. Inactive stores are `null`.
- **`GET /schema/mapping/{collection}`** — static field→physical mapping per store (tables/columns/FKs, Mongo keys and `_sub_`/`_con_` arrays, Neo4j label/relationships with direction) plus `connectionData` field mappings. Pure metadata, no query execution. Implemented in `server/src/main/kotlin/server/Inspection.kt`.

### Schema (`GET /schema`)

`DatabaseSchema` (`model/DatabaseSchema.kt`) serialized by kotlinx:

```json
{
  "collections": [
    { "name": "users", "schema": { "age": "INT", "name": "STRING" }, "childCollections": ["orders"], "parentCollection": null }
  ],
  "connections": [
    { "name": "ordered", "collection1Name": "orders", "collection2Name": "products", "connectionDataSchema": { "quantity": "INT" } }
  ]
}
```

`DataType` serializes as its enum name: `"STRING" | "INT" | "FLOAT" | "UUID" | "BOOLEAN" | "NULL"`. The schema is not defined in a file — it is parsed at startup from the live stores (`DriverManager.parseDatabaseSchema`, `Main.kt:53`), which must all agree.

## polybrowse (`polybrowse/`)

polybrowse is a **demo-oriented** management interface for the polystore. It has two purposes:

1. Browse and filter data **in the polystore's own schema**: collections, nested subcollections and connections.
2. At any point, show **how the same data is physically stored** in PostgreSQL, MongoDB and Neo4j.

Basic mutations are a secondary goal. All communication with the polystore goes through the HTTP API.

**The existing feature code in `polybrowse/src/` is a discarded early test** (per explicit owner decision) — see `polybrowse/CLAUDE.md`. Build fresh; do not refactor or preserve it.

### Stack

- Vite and React with the React Compiler (configured)
- TypeScript (strict — must be enabled, see `polybrowse/CLAUDE.md`)
- TanStack Query (installed)
- TanStack Table (to be added)
- Tailwind CSS v4 (installed)
- shadcn/ui (installed, primitives generated in `src/components/ui/`)

A graph library such as React Flow or Cytoscape is added only when the Neo4j neighbourhood view is built.

### Core concept: the view *is* a QueryPath

Every view is the rendering of exactly one query path (the `path` array of `/query/get`):

- Navigating into a collection, a subcollection or a connection **appends a segment**.
- Adding a filter **sets/extends the segment's `condition` DSL string**.
- The current path is serialized into the **URL**. This gives back/forward navigation and shareable links, and it is also the source for the TanStack Query keys.

There is no separate "query mode" whose state could drift from what is shown.

### Layout

- **Schema sidebar (left).** A tree of the schema from `GET /schema`. Fields show their `DataType`. Subcollections and connections have distinct icons. Clicking a node starts a new path.
- **Path bar (top).** The query path as breadcrumb chips, each with filter badges:
  - Clicking a chip opens its filter editor.
  - "×" on a chip truncates the path back to that point.
  - A "+ filter" control adds a filter to the current segment.
- **Result table (centre).** The instances of the last segment:
  - Scalar fields appear as columns.
  - Subcollection and connection columns show **counts only**. Clicking a count appends a segment.
  - Never eagerly load the targets of a fan-out.
- **Raw query panel (below the table).** The request JSON, **editable and two-way synced** with the path bar. It is validated against the TS types before sending.
- **Inspector (right).** The selected instance, with these tabs:
  - Fields (editable)
  - Subcollections (lazy tree)
  - Connections (searchable)
  - Storage

### Storage lens

The tabs `Polystore | PostgreSQL | MongoDB | Neo4j` switch the view at three levels:

1. **Schema mapping.** How the current collection is laid out physically in each store (see the verified naming table above).
2. **Raw data.** For the selected instance: the Postgres rows, the raw Mongo document, the Neo4j node and neighbourhood.
3. **Native queries.** For the current path: the generated SQL, Mongo pipeline and Cypher side by side, which driver the router chose, and optionally per-driver timing and a "force driver" toggle.

**Linked highlighting** is the key UX feature: hovering a polystore field highlights the corresponding column, JSON key or node property in every store, and vice versa.

Backend support (all implemented, see "Inspection routes" above):

- Router choice + the executed native query of the **chosen** driver: in the `/query/get` response (`env`).
- Native queries for **all** drivers without executing + routing decision: `POST /query/explain`.
- Raw per-store instance data: `POST /instance/raw`.
- Field→physical mapping: `GET /schema/mapping/{collection}`.
- Force driver: the optional `forceDriver` field on `/query/get`.

### Filtering

- The filter editor is type-aware per `DataType` and compiles to the condition DSL. Numeric ranges compile to `field > a && field < b` (open ends allowed; note `<=`/`>=` are not supported by the server parser).
- Filters attach to the segment they belong to, e.g. `users [age 30–50] › orders [status=open]`.

### Mutations (basic)

All mutation forms are generated from the schema:

- Edit scalar fields of an instance via `POST /document/update` (dynamic/non-schema fields are not updatable — Postgres `updateDocument` has a `// TODO` for dynamic data).
- Add an instance to a collection or to a subcollection of the selected instance (`POST /document/insert` with `parentDocUuid`).
- Delete an instance — **no delete-instance route exists yet** (only `/collection/drop` for whole collections).
- Add connections via `POST /connection/insert`, using a searchable picker that reuses the filter builder. Removing a single connection instance has no route yet.

After a write, invalidate the affected query keys. A write reaches **all three stores** automatically (server-side fan-out) — the client calls one route only.

### Explicitly out of scope

- Pagination, limit/offset and virtualization. Datasets are demo-sized. (`GetQuery.limit` exists internally but is not exposed over HTTP.)
- Authentication
- Production hardening

### Code conventions

- **Type-level enforcement over runtime checks.** Mirror the Kotlin shapes as TypeScript types. Segments are a discriminated union on the `type: "collection" | "connection"` string field. Filters are **not** JSON unions — model the condition DSL as a typed builder/AST in the frontend that serializes to the DSL string.
- **Exhaustive handling.** Every `switch` over a union ends with an exhaustiveness check (`const _: never = x`). A new backend variant must cause a type error, not a silent gap.
- **Derive the TS types from the Kotlin source.** Do not invent them. When the Kotlin types change, update the TS types in the same change.
- **One typed API client** in `src/api/`. Components never call `fetch` directly.
- **Suggested module split**:

  | Module | Contents |
  |---|---|
  | `api/` | Client and types |
  | `query/` | Query-path state, URL (de)serialization, query keys |
  | `schema/` | Schema tree and form generation |
  | `browse/` | Path bar, table, inspector |
  | `lens/` | Storage views |
  | `mutate/` | Forms and pickers |

- **Use shadcn/ui components** before writing custom ones.

### Dev setup

- Server: `./gradlew run` in `server/` → Ktor on `http://localhost:8080`. Requires local Postgres (5432), MongoDB (27017) and Neo4j (7687) per `Main.kt`.
- Frontend: `npm run dev` in `polybrowse/` → Vite on the default port 5173.
- **Gap to close:** `vite.config.ts` has no `server.proxy` yet. Add one proxying the API routes to `http://localhost:8080` as part of the rebuild. Do **not** rely on the server's open CORS; the discarded prototype's manual IP/port entry must not carry forward.

## Build order

1. **Contract.** Generate TS types from Kotlin and design the additive backend routes (approval needed).
2. **Browsing.** Schema sidebar, path bar, result table and URL-encoded query path.
3. **Filtering.** Filter editor and the two-way synced raw JSON panel.
4. **Inspector.** Fields, lazy subcollections and connections.
5. **Storage lens.** Native queries first, then raw data, then schema mapping with linked highlighting.
6. **Mutations.**

## Working style

- Plan before larger steps, and work in small, reviewable increments.
- Ground decisions in the actual code. Cite file paths when explaining how something works.
- Ask when an assumption in this file turns out to be wrong. Do not silently work around it.
