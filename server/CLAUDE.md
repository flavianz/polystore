# server/ — polystore backend

Kotlin + Ktor (Netty) + Gradle Kotlin DSL. See the root `CLAUDE.md` for the full verified API contract; this file covers server-specific commands, structure and constraints.

## Commands

```
./gradlew run      # start the server on http://localhost:8080
./gradlew build
./gradlew test
./gradlew buildFatJar   # Shadow packaging (Ktor plugin)
```

Main class: `ch.flavianz.MainKt` (`build.gradle.kts:49`), entry point `src/main/kotlin/Main.kt`.

## Configuration

There are **no config files and no env vars** — everything is hardcoded:

- Port 8080: `src/main/kotlin/server/Server.kt:28`
- Postgres `127.0.0.1:5432/polystore` (user `postgres` / `password`): `Main.kt:16-22`
- MongoDB `127.0.0.1:27017/polystore`: `Main.kt:24-29`
- Neo4j bolt `127.0.0.1:7687` (user `neo4j` / `password`): `Main.kt:31-35`, defaults in `connection/Neo4jConnection.kt:12-18`

`src/main/resources` is empty; Ktor is configured programmatically in `startServer()`. If a different DB target is ever needed, that is an edit to `Main.kt`, not a config file.

All three stores must be running and hold identical schemas — startup parses the schema from the live stores and asserts agreement (`driver/DriverManager.kt:322-347`, called from `Main.kt:53`).

## Structure

- `server/Server.kt` — the single `routing {}` block (lines 38–304). **New routes go here**, following the existing pattern: `call.receive<Dto>()` → `runCatching { DatabaseManager.xyz(...) }` → `.fold(onSuccess, onFailure)`.
- `server/RequestDefinitions.kt` — request DTOs (`@Serializable data class`) and `JsonElement.toPolyValue()`.
- `server/ConditionParser.kt` — recursive-descent parser for the condition DSL string.
- `core/DatabaseManager.kt` — the logical layer: schema registry, write fan-out, read entry point (`get`).
- `driver/` — `DatabaseDriver` interface + `PostgresDriver` / `MongoDriver` / `Neo4jDriver`, `DriverManager` (holds the three driver instances, routes reads, fans out writes), `RegressionModel`.
- `query/` — `GetQuery`, `QueryPath`, `QuerySegment`, `Condition`, `QueryResult`.
- `model/` — `DataType`, `PolySchema`, `PolyData`, `CollectionModel`, `ConnectionModel`, `DatabaseSchema`.
- `benchmark/` — benchmark harness and environments.

## Serialization — two styles, don't mix them up

- **Requests** and the schema/list responses use plain `@Serializable data class` + Ktor's default `json()` ContentNegotiation. No polymorphism, no `SerializersModule`, no class discriminators anywhere.
- **Query results** are hand-built with `kotlinx.serialization.json.buildJsonObject` (`query/QueryResult.kt`) and responded as a `String`. A new result type will not serialize "automatically" — follow whichever style the neighbouring code uses.

## Off-limits (thesis results depend on this code)

Do not modify behavior in:

- `driver/RegressionModel.kt`
- `driver/DriverManager.kt`: `chooseDriverModel`, `chooseDriverSimple`, `benchmarkGet`
- `driver/TimedDriverResult.kt`, `driver/TimedQueryValue.kt`
- `benchmark/**` (including `benchmark/regression/` and `benchmark/environments/`)
- The query-building/translation internals of the three drivers: `PostgresDriver` (`buildSelectClause`, `appendFromAndJoins`, `appendWhere`, `translateCondition`), `MongoDriver` (`conditionToFilter`, `fetch*Segment` and the multi-hop `get` branch), `Neo4jDriver` (`buildMatchClause`, `buildWhereClause`, `buildReturnClause`, `translateCondition`)
- Query semantics: `query/Condition.kt`, `query/QuerySegment.kt`, `query/QueryPath.kt`, `server/ConditionParser.kt`

Additive changes (new routes, new optional request fields, new read-only driver methods that don't alter existing paths) are the only acceptable kind, and need an approved plan first. Commit server changes separately from frontend changes.

## Useful facts

- `GetQuery.limit` exists but is not reachable via HTTP (`QueryRequest` has no limit field).
- The routing decision is surfaced to clients (`env.driver` + `env.executedQueries` in the `/query/get` response); the per-driver regression scores are computed and discarded inside `RegressionModel.calculateFastestDriverRegression`.
- Driver instances are public vars on `DriverManager` (`postgresDriver`, `mongoDriver`, `neo4jDriver`) — the benchmark code calls them directly to bypass routing.
- Connection update is an unimplemented `// TODO` (`driver/DatabaseDriver.kt:26`); document update is routed via `POST /document/update` but skips dynamic (non-schema) data in Postgres (`PostgresDriver.updateDocument` TODO).

## Inspection additions (polybrowse support)

Added additively for the frontend; none of them alter existing query execution or routing:

- `server/Inspection.kt` — response builders for the inspection routes plus `parseQuerySegments` (same parsing as `/query/get`) and `parseDriverType`.
- Each driver has two new read-only public methods: `explain(query)` (builds the native query string(s) by composing the same private builders `get()` uses, without executing; Mongo reports multi-hop paths as having no single native query) and `getRawInstance(...)` (raw row(s)/document/node+relationships for one UUID).
- `DriverManager.get` / `DatabaseManager.get` accept an optional `forceDriver: DriverType?` (default null = unchanged routed path); `DriverManager.driverFor(type)` resolves it. `chooseDriverModel`/`chooseDriverSimple` are untouched.
- Routes: `POST /query/explain`, `POST /instance/raw`, `POST /document/update`, `GET /schema/mapping/{collection}`, and the optional `forceDriver` field on `POST /query/get`. Request/response shapes are documented in the root `CLAUDE.md`.
