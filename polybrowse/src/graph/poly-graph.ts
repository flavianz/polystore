/**
 * The abstract model: collections with typed fields, subcollections hanging
 * off their parent, and each connection as a chip between its two sides.
 * Connections are undirected here — direction only exists in Neo4j's storage
 * and, at query time, in path order.
 */
import type { DatabaseSchema } from '@/api/types';
import {
    collectionNodeId,
    connectionNodeId,
    sortedByName,
    type GraphEdge,
    type GraphNode,
    type StoreGraph,
} from './graph-model';
import { mappingKey } from './mapping-key';

export function buildPolyGraph(schema: DatabaseSchema): StoreGraph {
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
                sourceRow: null,
                targetRow: null,
                label: 'subcollection',
                rows: [],
                kind: 'contains',
                arrow: 'target',
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
        const side = (n: 1 | 2, source: string, target: string): GraphEdge => ({
            id: `con:${connection.name}:${n}`,
            source,
            target,
            sourceRow: null,
            targetRow: null,
            label: null,
            rows: [],
            kind: 'connection',
            arrow: 'none',
            keys: [key],
        });
        edges.push(
            side(1, collectionNodeId(connection.collection1Name), id),
            side(2, id, collectionNodeId(connection.collection2Name)),
        );
    }

    return { nodes, edges };
}
