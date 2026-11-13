/**
 * Read helpers over DatabaseSchema (GET /schema).
 *
 * Note on connections: a ConnectionModel names collection1 and collection2,
 * and either side may be the one we are standing on. "Direction" is not part
 * of the query model — it follows from path order — so what matters here is
 * only which collection is on the *other* side.
 */
import type { CollectionModel, ConnectionModel, DatabaseSchema, DataType } from '@/api/types';

export interface ConnectionFromCollection {
    readonly connection: ConnectionModel;
    /** The collection reached by traversing this connection. */
    readonly otherCollection: string;
    /** Whether this collection is collection1 (i.e. the stored edge points away from it). */
    readonly isOutgoing: boolean;
    /** True when both sides are the same collection. */
    readonly isSelfReferential: boolean;
}

export function collectionByName(
    schema: DatabaseSchema,
    name: string,
): CollectionModel | undefined {
    return schema.collections.find((collection) => collection.name === name);
}

export function connectionByName(
    schema: DatabaseSchema,
    name: string,
): ConnectionModel | undefined {
    return schema.connections.find((connection) => connection.name === name);
}

/** Top-level collections — those without a parent. */
export function rootCollections(schema: DatabaseSchema): readonly CollectionModel[] {
    return schema.collections
        .filter((collection) => collection.parentCollection === null)
        .toSorted((a, b) => a.name.localeCompare(b.name));
}

export function childCollections(
    schema: DatabaseSchema,
    name: string,
): readonly CollectionModel[] {
    return schema.collections
        .filter((collection) => collection.parentCollection === name)
        .toSorted((a, b) => a.name.localeCompare(b.name));
}

/** Every connection reachable from this collection, with the far side resolved. */
export function connectionsOf(
    schema: DatabaseSchema,
    name: string,
): readonly ConnectionFromCollection[] {
    return schema.connections
        .filter(
            (connection) =>
                connection.collection1Name === name || connection.collection2Name === name,
        )
        .map((connection) => {
            const isOutgoing = connection.collection1Name === name;
            return {
                connection,
                otherCollection: isOutgoing
                    ? connection.collection2Name
                    : connection.collection1Name,
                isOutgoing,
                isSelfReferential: connection.collection1Name === connection.collection2Name,
            };
        })
        .toSorted((a, b) => a.connection.name.localeCompare(b.connection.name));
}

/** Field names of a collection, with the synthetic _id first. */
export function fieldNames(collection: CollectionModel): readonly string[] {
    return ['_id', ...Object.keys(collection.schema)];
}

/** The DataType of a field, or null for _id / unknown (dynamic) fields. */
export function fieldType(collection: CollectionModel, field: string): DataType | null {
    return collection.schema[field] ?? null;
}
