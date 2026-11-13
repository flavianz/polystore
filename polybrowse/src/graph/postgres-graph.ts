/**
 * PostgreSQL as an ER diagram: one `ps_col_<x>` table per collection, one
 * `ps_con_<y>` join table per connection, and column-level foreign keys.
 *
 * Physical names come from GET /schema/mapping. What the mapping does not
 * state is taken from the DDL in PostgresDriver.createCollection /
 * createConnection: `_id` is the primary key, every table (join tables too)
 * gets a `_dynamic_data JSONB` column, and FKs reference `_id`.
 */
import type { DatabaseSchema } from '@/api/types';
import {
    collectionNodeId,
    connectionNodeId,
    mappingOf,
    sortedByName,
    type GraphEdge,
    type GraphNode,
    type GraphRow,
    type Mappings,
    type StoreGraph,
} from './graph-model';
import { mappingKey } from './mapping-key';
import { postgresType } from './physical-types';

const DYNAMIC_HINT = 'Fields that are not in the poly schema, stored as JSON';

export function buildPostgresGraph(schema: DatabaseSchema, mappings: Mappings): StoreGraph {
    const nodes: GraphNode[] = [];
    const edges: GraphEdge[] = [];

    for (const collection of sortedByName(schema.collections)) {
        const pg = mappingOf(mappings, collection.name).postgres;
        const parent = collection.parentCollection;
        const rows: GraphRow[] = [
            {
                id: pg.idColumn,
                label: pg.idColumn,
                detail: 'UUID · PK',
                tone: 'id',
                keys: [mappingKey.collection(collection.name)],
            },
        ];

        if (parent && pg.parentFkColumn && pg.parentTable) {
            rows.push({
                id: pg.parentFkColumn,
                label: pg.parentFkColumn,
                detail: 'UUID · FK',
                tone: 'reference',
                keys: [mappingKey.subcollection(parent, collection.name)],
                hint: `References ${pg.parentTable}._id — the owning ${parent} instance`,
            });
            edges.push({
                id: `fk:${collection.name}:parent`,
                source: collectionNodeId(parent),
                target: collectionNodeId(collection.name),
                sourceRow: mappingOf(mappings, parent).postgres.idColumn,
                targetRow: pg.parentFkColumn,
                label: null,
                rows: [],
                kind: 'foreignKey',
                arrow: 'source',
                keys: [mappingKey.subcollection(parent, collection.name)],
            });
        }

        for (const [field, column] of Object.entries(pg.fields)) {
            rows.push({
                id: column.column,
                label: column.column,
                detail: column.type,
                tone: 'field',
                keys: [mappingKey.field(collection.name, field)],
            });
        }

        rows.push({
            id: pg.dynamicDataColumn,
            label: pg.dynamicDataColumn,
            detail: 'JSONB',
            tone: 'meta',
            keys: [],
            hint: DYNAMIC_HINT,
        });

        nodes.push({
            id: collectionNodeId(collection.name),
            kind: 'entity',
            title: pg.table,
            caption: parent
                ? `table · subcollection ${collection.name}`
                : `table · collection ${collection.name}`,
            colorOf: collection.name,
            keys: [mappingKey.collection(collection.name)],
            rows,
        });
    }

    for (const connection of sortedByName(schema.connections)) {
        const side1 = connection.collection1Name;
        const side2 = connection.collection2Name;
        const tables = mappingOf(mappings, side1).postgres.connectionTables[connection.name];
        if (!tables) {
            throw new Error(`mapping of ${side1} does not list connection ${connection.name}`);
        }
        const key = mappingKey.connection(connection.name);
        const nodeId = connectionNodeId(connection.name);
        const dataMapping = mappingOf(mappings, side1).connectionData[connection.name] ?? {};

        const rows: GraphRow[] = [
            {
                id: tables.fkColumnThisSide,
                label: tables.fkColumnThisSide,
                detail: 'UUID · FK',
                tone: 'reference',
                keys: [key],
                hint: `References ps_col_${side1}._id`,
            },
            {
                id: tables.fkColumnOtherSide,
                label: tables.fkColumnOtherSide,
                detail: 'UUID · FK',
                tone: 'reference',
                keys: [key],
                hint: `References ps_col_${side2}._id`,
            },
            ...Object.entries(dataMapping).map(([field, column]) => ({
                id: column.postgresColumn,
                label: column.postgresColumn,
                detail: postgresType(column.type),
                tone: 'field' as const,
                keys: [mappingKey.connectionField(connection.name, field)],
            })),
            {
                id: '_dynamic_data',
                label: '_dynamic_data',
                detail: 'JSONB',
                tone: 'meta',
                keys: [],
                hint: DYNAMIC_HINT,
            },
        ];

        nodes.push({
            id: nodeId,
            kind: 'entity',
            title: tables.table,
            caption: `join table · ${side1} ↔ ${side2}`,
            colorOf: null,
            keys: [key],
            rows,
        });

        const fk = (n: 1 | 2, collection: string, column: string): GraphEdge => ({
            id: `fk:${connection.name}:${n}`,
            source: collectionNodeId(collection),
            target: nodeId,
            sourceRow: mappingOf(mappings, collection).postgres.idColumn,
            targetRow: column,
            label: null,
            rows: [],
            kind: 'foreignKey',
            arrow: 'source',
            keys: [key],
        });
        edges.push(fk(1, side1, tables.fkColumnThisSide), fk(2, side2, tables.fkColumnOtherSide));
    }

    return { nodes, edges };
}
