/**
 * MongoDB, drawn as the shape of each collection's documents. Unlike the other
 * stores, MongoDB denormalizes, and this view is meant to make that visible:
 *
 * - Every collection, subcollections included, is its own Mongo collection
 *   holding full documents (MongoDriver.insertDocument, line 85).
 * - A subcollection document is *also* pushed, in full, into its parent's
 *   `_sub_<child>` array (line 72). The parent's parent only gets the child's
 *   id, inside the embedded parent entry (lines 76-81) — and nothing deeper.
 * - A connection is pushed into the `_con_<name>` array of *both* documents as
 *   `{_rel: <connection data>, _doc: <copy of the other document>}` (lines
 *   152-188).
 *
 * Physical key names come from GET /schema/mapping.
 */
import type { DatabaseSchema } from '@/api/types';
import {
    collectionNodeId,
    mappingOf,
    sortedByName,
    type GraphEdge,
    type GraphNode,
    type GraphRow,
    type Mappings,
    type StoreGraph,
} from './graph-model';
import { mappingKey } from './mapping-key';
import { mongoType } from './physical-types';

export function buildMongoGraph(schema: DatabaseSchema, mappings: Mappings): StoreGraph {
    const nodes: GraphNode[] = [];
    const edges: GraphEdge[] = [];

    for (const collection of sortedByName(schema.collections)) {
        const mongo = mappingOf(mappings, collection.name).mongo;
        const rows: GraphRow[] = [...ownRows(schema, mappings, collection.name, '', 0)];

        // Embedded subcollection arrays: full copies of the child documents.
        for (const [child, arrayKey] of sortedEntries(mongo.subcollectionArrays)) {
            rows.push({
                id: arrayKey,
                label: arrayKey,
                detail: 'array',
                tone: 'container',
                keys: [mappingKey.subcollection(collection.name, child)],
                hint: `Full copies of the ${child} documents owned by this ${collection.name}`,
            });
            rows.push(...ownRows(schema, mappings, child, `${arrayKey}.`, 1));
            edges.push({
                id: `embed:${collection.name}>${child}`,
                source: collectionNodeId(collection.name),
                target: collectionNodeId(child),
                sourceRow: arrayKey,
                targetRow: null,
                label: 'embedded copy',
                rows: [],
                kind: 'embeddedCopy',
                arrow: 'target',
                keys: [mappingKey.subcollection(collection.name, child)],
            });

            // Inside the embedded child: only the ids of the grandchildren.
            const childMongo = mappingOf(mappings, child).mongo;
            for (const [grandchild, grandKey] of sortedEntries(childMongo.subcollectionArrays)) {
                const rowId = `${arrayKey}.${grandKey}`;
                rows.push({
                    id: rowId,
                    label: grandKey,
                    detail: 'array<UUID>',
                    tone: 'reference',
                    keys: [mappingKey.subcollection(child, grandchild)],
                    hint: `Only the _id values of the ${grandchild} documents, not the documents`,
                    depth: 1,
                });
                edges.push({
                    id: `ids:${collection.name}>${child}>${grandchild}`,
                    source: collectionNodeId(collection.name),
                    target: collectionNodeId(grandchild),
                    sourceRow: rowId,
                    targetRow: null,
                    label: 'ids only',
                    rows: [],
                    kind: 'idReference',
                    arrow: 'target',
                    keys: [mappingKey.subcollection(child, grandchild)],
                });
            }
        }

        // Connection arrays: connection data plus a copy of the other side.
        for (const [connectionName, arrayKey] of sortedEntries(mongo.connectionArrays)) {
            const connection = schema.connections.find((c) => c.name === connectionName);
            if (!connection) continue;
            const other =
                connection.collection1Name === collection.name
                    ? connection.collection2Name
                    : connection.collection1Name;
            const data =
                mappingOf(mappings, collection.name).connectionData[connectionName] ?? {};
            const key = mappingKey.connection(connectionName);

            rows.push(
                {
                    id: arrayKey,
                    label: arrayKey,
                    detail: 'array',
                    tone: 'container',
                    keys: [key],
                    hint: `One entry per ${connectionName} connection; the same entry also sits in the ${other} document`,
                },
                {
                    id: `${arrayKey}._rel`,
                    label: '_rel',
                    detail: 'object',
                    tone: 'container',
                    keys: [key],
                    hint: 'The data of the connection itself',
                    depth: 1,
                },
                ...sortedEntries(data).map(([field, location]) => ({
                    id: `${arrayKey}.${location.mongoKey}`,
                    label: location.mongoKey.split('.').at(-1) ?? field,
                    detail: mongoType(location.type),
                    tone: 'field' as const,
                    keys: [mappingKey.connectionField(connectionName, field)],
                    depth: 2,
                })),
                {
                    id: `${arrayKey}._doc`,
                    label: '_doc',
                    detail: `{ ${other} }`,
                    tone: 'reference',
                    keys: [mappingKey.collection(other)],
                    hint: `A copy of the connected ${other} document; its own _con_ arrays are reduced to ids`,
                    depth: 1,
                },
            );

            // One edge per connection, between the two arrays, drawn from side 1.
            if (connection.collection1Name === collection.name && other !== collection.name) {
                const otherKey = mappingOf(mappings, other).mongo.connectionArrays[connectionName];
                edges.push({
                    id: `dup:${connectionName}`,
                    source: collectionNodeId(collection.name),
                    target: collectionNodeId(other),
                    sourceRow: arrayKey,
                    targetRow: otherKey ?? null,
                    label: 'in both documents',
                    rows: [],
                    kind: 'denormalized',
                    arrow: 'both',
                    keys: [key],
                });
            }
        }

        nodes.push({
            id: collectionNodeId(collection.name),
            kind: 'document',
            title: mongo.collection,
            caption:
                mongo.parentCollection && mongo.embeddedInParentAs
                    ? `collection · also in ${mongo.parentCollection}.${mongo.embeddedInParentAs}`
                    : 'collection',
            colorOf: collection.name,
            keys: [mappingKey.collection(collection.name)],
            rows,
        });
    }

    return { nodes, edges };
}

/** `_id` plus the schema fields of one collection's documents. */
function ownRows(
    schema: DatabaseSchema,
    mappings: Mappings,
    collection: string,
    idPrefix: string,
    depth: number,
): GraphRow[] {
    const mongo = mappingOf(mappings, collection).mongo;
    const types = schema.collections.find((c) => c.name === collection)?.schema ?? {};
    return [
        {
            id: `${idPrefix}${mongo.idKey}`,
            label: mongo.idKey,
            detail: 'UUID',
            tone: 'id',
            keys: [mappingKey.collection(collection)],
            depth,
        },
        ...Object.entries(mongo.fields).map(([field, location]) => ({
            id: `${idPrefix}${location.key}`,
            label: location.key,
            detail: mongoType(types[field] ?? 'NULL'),
            tone: 'field' as const,
            keys: [mappingKey.field(collection, field)],
            depth,
        })),
    ];
}

function sortedEntries<V>(record: Readonly<Record<string, V>>): [string, V][] {
    return Object.entries(record).toSorted(([a], [b]) => a.localeCompare(b));
}
