/**
 * Mapping keys: the identity of one *poly* schema element, attached to every
 * element drawn in any of the four schema graphs. Two drawn elements that carry
 * the same key represent the same poly concept — that is what linked
 * highlighting matches on.
 */
import { assertNever } from '@/lib/assert-never';

export type MappingKey =
    | { readonly kind: 'collection'; readonly collection: string }
    | { readonly kind: 'field'; readonly collection: string; readonly field: string }
    | { readonly kind: 'subcollection'; readonly parent: string; readonly child: string }
    | { readonly kind: 'connection'; readonly connection: string }
    | { readonly kind: 'connectionField'; readonly connection: string; readonly field: string };

export const mappingKey = {
    collection: (collection: string): MappingKey => ({ kind: 'collection', collection }),
    field: (collection: string, field: string): MappingKey => ({
        kind: 'field',
        collection,
        field,
    }),
    subcollection: (parent: string, child: string): MappingKey => ({
        kind: 'subcollection',
        parent,
        child,
    }),
    connection: (connection: string): MappingKey => ({ kind: 'connection', connection }),
    connectionField: (connection: string, field: string): MappingKey => ({
        kind: 'connectionField',
        connection,
        field,
    }),
};

/** A stable string form, used for set membership. */
export function keyId(key: MappingKey): string {
    switch (key.kind) {
        case 'collection':
            return `col:${key.collection}`;
        case 'field':
            return `field:${key.collection}.${key.field}`;
        case 'subcollection':
            return `sub:${key.parent}>${key.child}`;
        case 'connection':
            return `con:${key.connection}`;
        case 'connectionField':
            return `conf:${key.connection}.${key.field}`;
        default:
            return assertNever(key);
    }
}
