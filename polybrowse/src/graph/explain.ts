/**
 * "How is this stored?" — a short plain-language explanation of one poly
 * schema element in each store, generated from GET /schema and the storage
 * mappings. Physical names come from the mapping; the storage behaviour
 * described is the one documented in the per-store graph builders.
 *
 * Text uses `backticks` for code; the card renders those as <code>.
 */
import type { DatabaseSchema } from '@/api/types';
import { assertNever } from '@/lib/assert-never';
import { mappingOf, type Mappings } from './graph-model';
import type { MappingKey } from './mapping-key';
import { mongoType, neo4jType, postgresType } from './physical-types';

export type PhysicalStore = 'postgres' | 'mongo' | 'neo4j';

export interface Explanation {
    readonly title: string;
    /** What the element is in the poly model. */
    readonly poly: string;
    readonly stores: Readonly<Record<PhysicalStore, readonly string[]>>;
    /** The collection a "Browse" shortcut should open, if any. */
    readonly browseCollection: string | null;
}

export function explain(
    key: MappingKey,
    schema: DatabaseSchema,
    mappings: Mappings,
): Explanation | null {
    switch (key.kind) {
        case 'collection':
            return explainCollection(key.collection, schema, mappings);
        case 'field':
            return explainField(key.collection, key.field, schema, mappings);
        case 'subcollection':
            return explainSubcollection(key.parent, key.child, schema, mappings);
        case 'connection':
            return explainConnection(key.connection, schema, mappings);
        case 'connectionField':
            return explainConnectionField(key.connection, key.field, schema, mappings);
        default:
            return assertNever(key);
    }
}

function explainCollection(
    name: string,
    schema: DatabaseSchema,
    mappings: Mappings,
): Explanation | null {
    const collection = schema.collections.find((c) => c.name === name);
    if (!collection) return null;
    const m = mappingOf(mappings, name);
    const parent = collection.parentCollection;
    const fieldCount = Object.keys(collection.schema).length;
    const grandparent = parent
        ? (schema.collections.find((c) => c.name === parent)?.parentCollection ?? null)
        : null;

    return {
        title: name,
        poly: parent
            ? `Subcollection of \`${parent}\` with ${fieldCount} field(s): every \`${name}\` instance belongs to exactly one \`${parent}\` instance.`
            : `Collection with ${fieldCount} field(s). Every instance gets a server-generated \`_id\` (UUID).`,
        stores: {
            postgres: [
                `Table \`${m.postgres.table}\`, one row per instance, \`${m.postgres.idColumn}\` is the primary key.`,
                ...(parent && m.postgres.parentFkColumn && m.postgres.parentTable
                    ? [
                          `Foreign key \`${m.postgres.parentFkColumn}\` references \`${m.postgres.parentTable}._id\`.`,
                      ]
                    : []),
                `Fields outside the schema go into \`${m.postgres.dynamicDataColumn}\` (JSONB).`,
            ],
            mongo: [
                `Collection \`${m.mongo.collection}\`, one document per instance.`,
                ...(parent && m.mongo.embeddedInParentAs
                    ? [
                          `Each document is also embedded in full in its parent's \`${parent}.${m.mongo.embeddedInParentAs}\` array — stored twice.`,
                      ]
                    : []),
                ...(grandparent && m.mongo.embeddedInParentAs
                    ? [
                          `The grandparent \`${grandparent}\` keeps only the ids, inside its embedded \`${parent}\` entries.`,
                      ]
                    : []),
            ],
            neo4j: [
                `Nodes with label \`${m.neo4j.label}\`; \`${m.neo4j.idProperty}\` is a string property.`,
                ...(parent && m.neo4j.parentRelationship
                    ? [
                          `Linked from the parent by \`(:ps_col_${parent})-[:${m.neo4j.parentRelationship}]->(:${m.neo4j.label})\`.`,
                      ]
                    : []),
            ],
        },
        browseCollection: name,
    };
}

function explainField(
    collectionName: string,
    field: string,
    schema: DatabaseSchema,
    mappings: Mappings,
): Explanation | null {
    const collection = schema.collections.find((c) => c.name === collectionName);
    const type = collection?.schema[field];
    if (!collection || !type) return null;
    const m = mappingOf(mappings, collectionName);
    const column = m.postgres.fields[field];
    const parent = collection.parentCollection;
    const copies = Object.keys(m.mongo.connectionArrays).map((connectionName) => {
        const connection = schema.connections.find((c) => c.name === connectionName);
        const other =
            connection?.collection1Name === collectionName
                ? connection.collection2Name
                : connection?.collection1Name;
        return `\`${other}._con_${connectionName}[]._doc.${field}\``;
    });

    return {
        title: `${collectionName}.${field}`,
        poly: `Field \`${field}\` of \`${collectionName}\`, type \`${type}\`.`,
        stores: {
            postgres: [
                `Column \`${m.postgres.table}.${column?.column ?? field}\` of type \`${column?.type ?? postgresType(type)}\`.`,
            ],
            mongo: [
                `Key \`${m.mongo.fields[field]?.key ?? field}\` (\`${mongoType(type)}\`) in every \`${m.mongo.collection}\` document.`,
                ...(parent && m.mongo.embeddedInParentAs
                    ? [
                          `Also in the embedded copies in \`${parent}.${m.mongo.embeddedInParentAs}\`.`,
                      ]
                    : []),
                ...(copies.length > 0
                    ? [`Also copied into connected documents: ${copies.join(', ')}.`]
                    : []),
            ],
            neo4j: [
                `Property \`${m.neo4j.fields[field]?.property ?? field}\` (\`${neo4jType(type)}\`) on \`:${m.neo4j.label}\` nodes.`,
            ],
        },
        browseCollection: collectionName,
    };
}

function explainSubcollection(
    parent: string,
    child: string,
    schema: DatabaseSchema,
    mappings: Mappings,
): Explanation | null {
    if (!schema.collections.some((c) => c.name === child)) return null;
    const m = mappingOf(mappings, child);
    const grandparent = schema.collections.find((c) => c.name === parent)?.parentCollection;

    return {
        title: `${parent} › ${child}`,
        poly: `\`${child}\` is a subcollection of \`${parent}\`: a one-to-many ownership. A query reaches it by appending \`${child}\` after \`${parent}\` in the path.`,
        stores: {
            postgres: [
                `Foreign key \`${m.postgres.table}.${m.postgres.parentFkColumn ?? 'ps_parent_fk'}\` → \`${m.postgres.parentTable ?? `ps_col_${parent}`}._id\`; a query joins on it.`,
            ],
            mongo: [
                `Child documents are embedded in full in \`${parent}.${m.mongo.embeddedInParentAs ?? `_sub_${child}`}\` and also stored in collection \`${m.mongo.collection}\`.`,
                ...(grandparent
                    ? [
                          `\`${grandparent}\` documents hold only the child ids, in \`_sub_${parent}[]._sub_${child}\`.`,
                      ]
                    : []),
            ],
            neo4j: [
                `Relationship \`(:ps_col_${parent})-[:${m.neo4j.parentRelationship ?? 'ps_parent_of'}]->(:${m.neo4j.label})\`; a query follows it.`,
            ],
        },
        browseCollection: child,
    };
}

function explainConnection(
    name: string,
    schema: DatabaseSchema,
    mappings: Mappings,
): Explanation | null {
    const connection = schema.connections.find((c) => c.name === name);
    if (!connection) return null;
    const { collection1Name: side1, collection2Name: side2 } = connection;
    const m1 = mappingOf(mappings, side1);
    const tables = m1.postgres.connectionTables[name];
    const relationship = m1.neo4j.connections[name];
    const fields = Object.keys(connection.connectionDataSchema);

    return {
        title: name,
        poly: `Connection between \`${side1}\` and \`${side2}\`: many-to-many${fields.length > 0 ? `, carrying ${fields.map((f) => `\`${f}\``).join(', ')}` : ''}. It can be traversed from either side.`,
        stores: {
            postgres: [
                `Join table \`${tables?.table ?? `ps_con_${name}`}\`, one row per connected pair.`,
                `\`${tables?.fkColumnThisSide ?? `ps_cfk_${side1}`}\` → \`ps_col_${side1}._id\`, \`${tables?.fkColumnOtherSide ?? `ps_cfk_${side2}`}\` → \`ps_col_${side2}._id\`.`,
            ],
            mongo: [
                `Each pair adds an entry \`{_rel, _doc}\` to \`${side1}._con_${name}\` and to \`${side2}._con_${name}\` — stored twice.`,
                '`_rel` holds the connection data, `_doc` a copy of the other document.',
            ],
            neo4j: [
                `One relationship \`(:ps_col_${side1})-[:${relationship?.relationshipType ?? name}]->(:ps_col_${side2})\` per pair.`,
                `Stored directed ${side1} → ${side2}, but queried in either direction.`,
            ],
        },
        browseCollection: null,
    };
}

function explainConnectionField(
    name: string,
    field: string,
    schema: DatabaseSchema,
    mappings: Mappings,
): Explanation | null {
    const connection = schema.connections.find((c) => c.name === name);
    const type = connection?.connectionDataSchema[field];
    if (!connection || !type) return null;
    const location = mappingOf(mappings, connection.collection1Name).connectionData[name]?.[field];
    const table =
        mappingOf(mappings, connection.collection1Name).postgres.connectionTables[name]?.table ??
        `ps_con_${name}`;

    return {
        title: `${name}.${field}`,
        poly: `Field \`${field}\` (\`${type}\`) carried by the \`${name}\` connection itself, not by either collection.`,
        stores: {
            postgres: [
                `Column \`${table}.${location?.postgresColumn ?? field}\` of type \`${postgresType(type)}\`.`,
            ],
            mongo: [
                `\`_con_${name}[].${location?.mongoKey ?? `_rel.${field}`}\` (\`${mongoType(type)}\`), in the documents of both sides.`,
            ],
            neo4j: [
                `Property \`${location?.neo4jProperty ?? field}\` (\`${neo4jType(type)}\`) on the \`:${name}\` relationship.`,
            ],
        },
        browseCollection: null,
    };
}
