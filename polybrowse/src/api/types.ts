/**
 * TypeScript mirrors of the polystore's Kotlin types.
 *
 * Derived from (do not invent shapes here — update together with the Kotlin):
 *   model/DataType.kt, model/PolySchema.kt, model/CollectionModel.kt,
 *   model/ConnectionModel.kt, model/DatabaseSchema.kt,
 *   server/RequestDefinitions.kt, query/QueryResult.kt, server/Inspection.kt
 */

// ── schema ────────────────────────────────────────────────────────────────

/** model/DataType.kt — a @Serializable enum, serialized as its name. */
export const DATA_TYPES = ['STRING', 'INT', 'FLOAT', 'UUID', 'BOOLEAN', 'NULL'] as const;
export type DataType = (typeof DATA_TYPES)[number];

/** model/PolySchema.kt — `typealias PolySchema = Map<String, DataType>`. */
export type PolySchema = Record<string, DataType>;

/** model/CollectionModel.kt */
export interface CollectionModel {
    readonly name: string;
    readonly schema: PolySchema;
    readonly childCollections: readonly string[];
    readonly parentCollection: string | null;
}

/** model/ConnectionModel.kt */
export interface ConnectionModel {
    readonly name: string;
    readonly collection1Name: string;
    readonly collection2Name: string;
    readonly connectionDataSchema: PolySchema;
}

/** model/DatabaseSchema.kt — Kotlin `Set`s serialize as JSON arrays. */
export interface DatabaseSchema {
    readonly collections: readonly CollectionModel[];
    readonly connections: readonly ConnectionModel[];
}

// ── queries ───────────────────────────────────────────────────────────────

/** query/QueryResult.kt — DriverType.toString() lowercases the name. */
export const DRIVER_TYPES = ['postgres', 'mongo', 'neo4j'] as const;
export type DriverType = (typeof DRIVER_TYPES)[number];

/**
 * server/RequestDefinitions.kt — RequestQuerySegment.
 *
 * `type` is the only discriminator the server has (a plain string compare in
 * Server.kt, not kotlinx polymorphism). `condition` is the condition DSL as a
 * string, parsed server-side by ConditionParser — not a JSON filter object.
 * All four keys must be present; condition/only may be null.
 */
export interface RequestQuerySegment {
    readonly name: string;
    readonly type: 'collection' | 'connection';
    readonly condition: string | null;
    readonly only: readonly string[] | null;
}

/** server/RequestDefinitions.kt — QueryRequest. */
export interface QueryRequest {
    readonly path: readonly RequestQuerySegment[];
    /** Optional: skips routing and runs this driver directly. */
    readonly forceDriver?: DriverType | null;
}

/** A field value as it comes back from the server (UUIDs arrive as strings). */
export type PolyFieldValue = string | number | boolean | null;

/** One result row: segment name → that segment's (projected) fields. */
export type PolyRow = Record<string, Record<string, PolyFieldValue>>;

/** query/QueryResult.kt — PolyResultData.toJson() */
export type PolyResultData =
    | { readonly type: 'documents'; readonly data: readonly PolyRow[] }
    | { readonly type: 'count'; readonly count: number };

/** query/QueryResult.kt — PolyQueryResult.toJson(). Durations are Kotlin Duration strings. */
export interface QueryResponse {
    readonly duration: {
        readonly queryBuilding: string;
        readonly queryExecuting: string;
        readonly processing: string;
    };
    readonly env: {
        readonly driver: DriverType;
        readonly executedQueries: readonly string[];
    };
    readonly data: PolyResultData;
}

// ── inspection (server/Inspection.kt) ─────────────────────────────────────

/** POST /query/explain */
export interface ExplainResponse {
    readonly routedDriver: DriverType | null;
    readonly routingError?: string;
    /** Only active drivers appear. Mongo reports multi-hop paths as having no single query. */
    readonly queries: Partial<Record<DriverType, readonly string[]>>;
}

/** POST /instance/raw — inactive stores come back as null. */
export interface RawInstanceResponse {
    readonly postgres: {
        readonly tables: Record<string, readonly Record<string, unknown>[]>;
    } | null;
    readonly mongo: {
        readonly document: Record<string, unknown> | null;
        readonly parentDocument: Record<string, unknown> | null;
    } | null;
    readonly neo4j: {
        readonly node: {
            readonly labels: readonly string[];
            readonly properties: Record<string, unknown>;
        } | null;
        readonly relationships: readonly {
            readonly type: string;
            readonly direction: 'outgoing' | 'incoming';
            readonly properties: Record<string, unknown>;
            readonly otherLabels: readonly string[];
            readonly otherId: string | null;
        }[];
    } | null;
}

/** GET /schema/mapping/{collection} */
export interface MappingResponse {
    readonly collection: string;
    readonly postgres: {
        readonly table: string;
        readonly idColumn: string;
        readonly dynamicDataColumn: string;
        readonly parentTable: string | null;
        readonly parentFkColumn: string | null;
        readonly fields: Record<string, { readonly column: string; readonly type: string }>;
        readonly connectionTables: Record<
            string,
            {
                readonly table: string;
                readonly fkColumnThisSide: string;
                readonly fkColumnOtherSide: string;
            }
        >;
    };
    readonly mongo: {
        readonly collection: string;
        readonly embeddedInParentAs: string | null;
        readonly parentCollection: string | null;
        readonly idKey: string;
        readonly fields: Record<string, { readonly key: string }>;
        readonly subcollectionArrays: Record<string, string>;
        readonly connectionArrays: Record<string, string>;
    };
    readonly neo4j: {
        readonly label: string;
        readonly idProperty: string;
        readonly parentRelationship: string | null;
        readonly fields: Record<string, { readonly property: string }>;
        readonly connections: Record<
            string,
            {
                readonly relationshipType: string;
                readonly direction: 'outgoing' | 'incoming';
                readonly otherCollection: string;
            }
        >;
    };
    readonly connectionData: Record<
        string,
        Record<
            string,
            {
                readonly postgresColumn: string;
                readonly mongoKey: string;
                readonly neo4jProperty: string;
                readonly type: DataType;
            }
        >
    >;
}

// ── writes (server/RequestDefinitions.kt) ─────────────────────────────────

/**
 * A field value being sent to the server. The server's JsonElement.toPolyValue()
 * reads a 36-char string as a UUID, and accepts `{type: "uuid", value: "..."}`
 * for an explicit one. Nested objects and arrays are rejected.
 */
export type PolyInputValue =
    | string
    | number
    | boolean
    | null
    | { readonly type: 'uuid'; readonly value: string };

export interface InsertDocumentRequest {
    readonly collection: string;
    readonly fields: Record<string, PolyInputValue>;
    /** Required (may be null) — the Kotlin field is not optional. */
    readonly parentDocUuid: string | null;
}

export interface UpdateDocumentRequest {
    readonly collection: string;
    readonly uuid: string;
    readonly fields: Record<string, PolyInputValue>;
}

export interface InsertConnectionRequest {
    readonly connection: string;
    readonly collection1Name: string;
    readonly collection1Uuid: string;
    readonly collection2Name: string;
    readonly collection2Uuid: string;
    readonly fields: Record<string, PolyInputValue>;
}

export interface RawInstanceRequest {
    readonly collection: string;
    readonly id: string;
}
