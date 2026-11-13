/**
 * Neo4j as a schema graph, in the spirit of `CALL db.schema.visualization()`:
 * one node label `ps_col_<x>` per collection, `ps_parent_of` relationships
 * from parent to child, and one relationship type per connection, stored as a
 * single directed edge collection1 → collection2 carrying the connection data
 * as relationship properties (Neo4jDriver.insertDocument / insertConnection).
 */
import type { DatabaseSchema } from '@/api/types';
import {
    collectionNodeId,
    mappingOf,
    sortedByName,
    type GraphEdge,
    type GraphNode,
    type Mappings,
    type StoreGraph,
} from './graph-model';
import { mappingKey } from './mapping-key';
import { neo4jType } from './physical-types';

export function buildNeo4jGraph(schema: DatabaseSchema, mappings: Mappings): StoreGraph {
    const nodes: GraphNode[] = [];
    const edges: GraphEdge[] = [];

    for (const collection of sortedByName(schema.collections)) {
        const neo = mappingOf(mappings, collection.name).neo4j;
        nodes.push({
            id: collectionNodeId(collection.name),
            kind: 'label',
            title: `:${neo.label}`,
            caption: `node label · ${collection.name}`,
            colorOf: collection.name,
            keys: [mappingKey.collection(collection.name)],
            rows: [
                {
                    id: neo.idProperty,
                    label: neo.idProperty,
                    detail: 'String',
                    tone: 'id',
                    keys: [mappingKey.collection(collection.name)],
                    hint: 'The UUID, stored as a string property',
                },
                ...Object.entries(neo.fields).map(([field, property]) => ({
                    id: property.property,
                    label: property.property,
                    detail: neo4jType(collection.schema[field] ?? 'NULL'),
                    tone: 'field' as const,
                    keys: [mappingKey.field(collection.name, field)],
                })),
            ],
        });

        const parent = collection.parentCollection;
        if (parent && neo.parentRelationship) {
            edges.push({
                id: `rel:${parent}>${collection.name}`,
                source: collectionNodeId(parent),
                target: collectionNodeId(collection.name),
                sourceRow: null,
                targetRow: null,
                label: `:${neo.parentRelationship}`,
                rows: [],
                kind: 'relationship',
                arrow: 'target',
                keys: [mappingKey.subcollection(parent, collection.name)],
            });
        }
    }

    for (const connection of sortedByName(schema.connections)) {
        const side1 = connection.collection1Name;
        const relationship = mappingOf(mappings, side1).neo4j.connections[connection.name];
        if (!relationship) {
            throw new Error(`mapping of ${side1} does not list connection ${connection.name}`);
        }
        const dataMapping = mappingOf(mappings, side1).connectionData[connection.name] ?? {};

        edges.push({
            id: `rel:${connection.name}`,
            // The stored edge always points collection1 → collection2.
            source: collectionNodeId(side1),
            target: collectionNodeId(connection.collection2Name),
            sourceRow: null,
            targetRow: null,
            label: `:${relationship.relationshipType}`,
            rows: Object.entries(dataMapping).map(([field, property]) => ({
                id: property.neo4jProperty,
                label: property.neo4jProperty,
                detail: neo4jType(property.type),
                tone: 'field' as const,
                keys: [mappingKey.connectionField(connection.name, field)],
            })),
            kind: 'relationship',
            arrow: 'target',
            keys: [mappingKey.connection(connection.name)],
        });
    }

    return { nodes, edges };
}
