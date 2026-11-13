/**
 * The schema-registry metadata each store keeps next to the data: the
 * polystore re-reads it at startup to rebuild the schema (all three stores
 * must agree). It has no poly counterpart, so its elements carry no mapping
 * keys and are drawn muted.
 *
 * The mapping route does not cover these, so names and shapes are taken from
 * the drivers — update together with them:
 * - PostgreSQL: PostgresDriver.init() (two tables, `fields` as JSONB).
 * - MongoDB:    MongoDriver.registerCollection / registerConnection
 *               (`fields` as an array of {name, type}).
 * - Neo4j:      Neo4jDriver.registerCollection / registerConnection —
 *               note the *singular* labels, `fields` as a JSON string.
 */
import { assertNever } from '@/lib/assert-never';
import type { GraphEdge, GraphNode, GraphRow, StoreGraph } from './graph-model';

export type ConfigStore = 'postgres' | 'mongo' | 'neo4j';

const COLLECTIONS_NODE = 'config:collections';
const CONNECTIONS_NODE = 'config:connections';

export function buildConfigGraph(store: ConfigStore): StoreGraph {
    switch (store) {
        case 'postgres':
            return {
                nodes: [
                    configNode(COLLECTIONS_NODE, 'entity', 'ps_config_collections', 'table', [
                        row('name', 'TEXT · PK', 'id'),
                        row('fields', 'JSONB'),
                        row(
                            'parent_collection',
                            'TEXT · FK',
                            'reference',
                            'References ps_config_collections.name',
                        ),
                    ]),
                    configNode(CONNECTIONS_NODE, 'entity', 'ps_config_connections', 'table', [
                        row('name', 'TEXT · PK', 'id'),
                        row('collection1', 'TEXT · FK', 'reference'),
                        row('collection2', 'TEXT · FK', 'reference'),
                        row('fields', 'JSONB'),
                    ]),
                ],
                edges: [fkEdge('collection1'), fkEdge('collection2')],
            };
        case 'mongo':
            return {
                nodes: [
                    configNode(
                        COLLECTIONS_NODE,
                        'document',
                        'ps_config_collections',
                        'collection',
                        [
                            row('name', 'string', 'id'),
                            row('fields', 'array', 'container'),
                            row('name', 'string', 'field', undefined, 1, 'fields.name'),
                            row('type', 'string', 'field', undefined, 1, 'fields.type'),
                            row('parent_collection', 'string | null'),
                        ],
                    ),
                    configNode(
                        CONNECTIONS_NODE,
                        'document',
                        'ps_config_connections',
                        'collection',
                        [
                            row('name', 'string', 'id'),
                            row('collection1', 'string'),
                            row('collection2', 'string'),
                            row('fields', 'array', 'container'),
                            row('name', 'string', 'field', undefined, 1, 'fields.name'),
                            row('type', 'string', 'field', undefined, 1, 'fields.type'),
                        ],
                    ),
                ],
                edges: [],
            };
        case 'neo4j':
            return {
                nodes: [
                    configNode(COLLECTIONS_NODE, 'label', ':ps_config_collection', 'node label', [
                        row('name', 'String', 'id'),
                        row('fields', 'String', 'field', 'The field list, JSON-encoded'),
                        row('parent_collection', 'String'),
                    ]),
                    configNode(CONNECTIONS_NODE, 'label', ':ps_config_connection', 'node label', [
                        row('name', 'String', 'id'),
                        row('collection1', 'String'),
                        row('collection2', 'String'),
                        row('fields', 'String', 'field', 'The field list, JSON-encoded'),
                    ]),
                ],
                edges: [],
            };
        default:
            return assertNever(store);
    }
}

function configNode(
    id: string,
    kind: GraphNode['kind'],
    title: string,
    what: string,
    rows: readonly GraphRow[],
): GraphNode {
    return {
        id,
        kind,
        title,
        caption: `${what} · schema registry`,
        colorOf: null,
        keys: [],
        rows,
        muted: true,
    };
}

function row(
    label: string,
    detail: string,
    tone: GraphRow['tone'] = 'field',
    hint?: string,
    depth?: number,
    id: string = label,
): GraphRow {
    return { id, label, detail, tone, keys: [], hint, depth };
}

function fkEdge(column: string): GraphEdge {
    return {
        id: `config-fk:${column}`,
        source: COLLECTIONS_NODE,
        target: CONNECTIONS_NODE,
        sourceRow: 'name',
        targetRow: column,
        label: null,
        rows: [],
        kind: 'foreignKey',
        arrow: 'source',
        keys: [],
    };
}
