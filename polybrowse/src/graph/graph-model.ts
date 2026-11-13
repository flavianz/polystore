/**
 * The schema as a graph: a plain typed model, no React Flow types.
 *
 * One builder per store view (poly-graph.ts, postgres-graph.ts, …) produces
 * nodes, rows and edges, every one tagged with the MappingKeys of the poly
 * elements it represents. Layout and rendering happen in layout.ts and
 * schema-graph.tsx.
 */
import type { MappingResponse } from '@/api/types';
import type { MappingKey } from './mapping-key';

/** How a row is styled. */
export type RowTone =
    /** The identifier of the instance (`_id`). */
    | 'id'
    /** A schema field / column / key / property. */
    | 'field'
    /** A reference to another instance (a foreign key). */
    | 'reference'
    /** Storage bookkeeping with no poly counterpart (e.g. `_dynamic_data`). */
    | 'meta'
    /** A nested array or object holding further rows (MongoDB `_sub_`, `_con_`, `_rel`). */
    | 'container';

export interface GraphRow {
    /** Unique within its node or edge; also the React Flow handle prefix. */
    readonly id: string;
    readonly label: string;
    /** Type or other detail shown right-aligned. */
    readonly detail: string | null;
    readonly tone: RowTone;
    readonly keys: readonly MappingKey[];
    /** Optional explanation shown as a native tooltip. */
    readonly hint?: string;
    /** Nesting level inside a document (MongoDB); 0 when absent. */
    readonly depth?: number;
}

export type GraphNodeKind =
    /** A card with a header and rows: a collection or a table. */
    | 'entity'
    /** A Neo4j node label: a rounded card. */
    | 'label'
    /** A MongoDB collection, drawn as the shape of its documents. */
    | 'document'
    /** A small chip standing for a poly connection, carrying its data fields. */
    | 'connection';

export interface GraphNode {
    readonly id: string;
    readonly kind: GraphNodeKind;
    readonly title: string;
    /** Small caption under the title ("subcollection of users", …). */
    readonly caption: string | null;
    /** The collection whose colour this node takes; null = neutral. */
    readonly colorOf: string | null;
    readonly keys: readonly MappingKey[];
    readonly rows: readonly GraphRow[];
}

export type GraphEdgeKind =
    /** Poly: parent owns child (a subcollection). */
    | 'contains'
    /** Poly: one side of a connection. */
    | 'connection'
    /** PostgreSQL: a foreign key column referencing a primary key. */
    | 'foreignKey'
    /** Neo4j: a relationship type between two labels. */
    | 'relationship'
    /** MongoDB: an embedded array whose documents are also stored in their own collection. */
    | 'embeddedCopy'
    /** MongoDB: an embedded array holding only the ids of another collection's documents. */
    | 'idReference'
    /** MongoDB: a connection, denormalized into the documents of both sides. */
    | 'denormalized';

/** Where the arrowhead goes: none, at the target, back at the source, or both. */
export type EdgeArrow = 'none' | 'target' | 'source' | 'both';

export interface GraphEdge {
    readonly id: string;
    readonly source: string;
    readonly target: string;
    /** Attach to a specific row of the source / target node instead of the node. */
    readonly sourceRow: string | null;
    readonly targetRow: string | null;
    readonly label: string | null;
    /** Properties carried by the edge itself (Neo4j relationship properties). */
    readonly rows: readonly GraphRow[];
    readonly kind: GraphEdgeKind;
    readonly arrow: EdgeArrow;
    readonly keys: readonly MappingKey[];
}

export interface StoreGraph {
    readonly nodes: readonly GraphNode[];
    readonly edges: readonly GraphEdge[];
}

/** The node standing for a collection, in every view. Focus targets this id. */
export function collectionNodeId(name: string): string {
    return `collection:${name}`;
}

/** The node standing for a connection, in views that draw it as a node. */
export function connectionNodeId(name: string): string {
    return `connection:${name}`;
}

/** GET /schema/mapping/{collection} for every collection, by collection name. */
export type Mappings = Readonly<Record<string, MappingResponse>>;

export function mappingOf(mappings: Mappings, collection: string): MappingResponse {
    const mapping = mappings[collection];
    if (!mapping) throw new Error(`no storage mapping loaded for ${collection}`);
    return mapping;
}

export function sortedByName<T extends { readonly name: string }>(
    items: readonly T[],
): readonly T[] {
    return items.toSorted((a, b) => a.name.localeCompare(b.name));
}
