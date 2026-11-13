/**
 * The schema as a graph, one flavour per store view.
 *
 * These are plain typed models (no React Flow types): a builder per view turns
 * GET /schema (plus, for the physical views, GET /schema/mapping) into nodes,
 * rows and edges, every one tagged with the MappingKeys of the poly elements it
 * represents. Layout and rendering happen in layout.ts / schema-graph.tsx.
 */
import type { DatabaseSchema } from '@/api/types';
import { assertNever } from '@/lib/assert-never';
import { mappingKey, type MappingKey } from './mapping-key';

export const STORE_VIEWS = ['poly'] as const;
export type StoreView = (typeof STORE_VIEWS)[number];

export function storeViewLabel(view: StoreView): string {
    switch (view) {
        case 'poly':
            return 'Polystore';
        default:
            return assertNever(view);
    }
}

/** How a row is styled: an identifier, a plain field, a reference, … */
export type RowTone = 'id' | 'field' | 'reference';

export interface GraphRow {
    readonly id: string;
    readonly label: string;
    /** Type or other detail shown right-aligned (DataType, pg type, …). */
    readonly detail: string | null;
    readonly tone: RowTone;
    readonly keys: readonly MappingKey[];
}

export type GraphNodeKind =
    /** A card with a header and rows: a collection, a table, a label. */
    | 'entity'
    /** A small chip standing for a connection, carrying its data fields. */
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
    /** Parent owns child (a subcollection). */
    | 'contains'
    /** One side of a connection. */
    | 'connection';

export interface GraphEdge {
    readonly id: string;
    readonly source: string;
    readonly target: string;
    readonly label: string | null;
    readonly kind: GraphEdgeKind;
    /** Draw an arrowhead at the target. */
    readonly directed: boolean;
    readonly keys: readonly MappingKey[];
}

export interface StoreGraph {
    readonly nodes: readonly GraphNode[];
    readonly edges: readonly GraphEdge[];
}

export function buildStoreGraph(view: StoreView, schema: DatabaseSchema): StoreGraph {
    switch (view) {
        case 'poly':
            return buildPolyGraph(schema);
        default:
            return assertNever(view);
    }
}

// ── Polystore ──────────────────────────────────────────────────────────────

/**
 * The abstract model: collections with typed fields, subcollections hanging
 * off their parent, and each connection as a chip between its two sides.
 * Connections are undirected here — direction only exists in Neo4j's storage
 * and, at query time, in path order.
 */
function buildPolyGraph(schema: DatabaseSchema): StoreGraph {
    const nodes: GraphNode[] = [];
    const edges: GraphEdge[] = [];

    for (const collection of sortedByName(schema.collections)) {
        const parent = collection.parentCollection;
        nodes.push({
            id: collectionNodeId(collection.name),
            kind: 'entity',
            title: collection.name,
            caption: parent ? `subcollection of ${parent}` : 'collection',
            colorOf: collection.name,
            keys: [mappingKey.collection(collection.name)],
            rows: [
                {
                    id: '_id',
                    label: '_id',
                    detail: 'UUID',
                    tone: 'id',
                    keys: [mappingKey.collection(collection.name)],
                },
                ...Object.entries(collection.schema).map(([field, type]) => ({
                    id: field,
                    label: field,
                    detail: type,
                    tone: 'field' as const,
                    keys: [mappingKey.field(collection.name, field)],
                })),
            ],
        });

        if (parent) {
            edges.push({
                id: `contains:${parent}>${collection.name}`,
                source: collectionNodeId(parent),
                target: collectionNodeId(collection.name),
                label: 'subcollection',
                kind: 'contains',
                directed: true,
                keys: [mappingKey.subcollection(parent, collection.name)],
            });
        }
    }

    for (const connection of sortedByName(schema.connections)) {
        const id = connectionNodeId(connection.name);
        const key = mappingKey.connection(connection.name);
        nodes.push({
            id,
            kind: 'connection',
            title: connection.name,
            caption: null,
            colorOf: null,
            keys: [key],
            rows: Object.entries(connection.connectionDataSchema).map(([field, type]) => ({
                id: field,
                label: field,
                detail: type,
                tone: 'field' as const,
                keys: [mappingKey.connectionField(connection.name, field)],
            })),
        });
        edges.push(
            {
                id: `con:${connection.name}:1`,
                source: collectionNodeId(connection.collection1Name),
                target: id,
                label: null,
                kind: 'connection',
                directed: false,
                keys: [key],
            },
            {
                id: `con:${connection.name}:2`,
                source: id,
                target: collectionNodeId(connection.collection2Name),
                label: null,
                kind: 'connection',
                directed: false,
                keys: [key],
            },
        );
    }

    return { nodes, edges };
}

/** The node standing for a collection, in every view. Focus targets this id. */
export function collectionNodeId(name: string): string {
    return `collection:${name}`;
}

export function connectionNodeId(name: string): string {
    return `connection:${name}`;
}

function sortedByName<T extends { readonly name: string }>(items: readonly T[]): readonly T[] {
    return items.toSorted((a, b) => a.name.localeCompare(b.name));
}
