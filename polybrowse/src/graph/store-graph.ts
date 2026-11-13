/**
 * The store views and the dispatch to their graph builders.
 */
import type { DatabaseSchema } from '@/api/types';
import { assertNever } from '@/lib/assert-never';
import type { Mappings, StoreGraph } from './graph-model';
import { buildNeo4jGraph } from './neo4j-graph';
import { buildPolyGraph } from './poly-graph';
import { buildPostgresGraph } from './postgres-graph';

export const STORE_VIEWS = ['poly', 'postgres', 'neo4j'] as const;
export type StoreView = (typeof STORE_VIEWS)[number];

export function storeViewLabel(view: StoreView): string {
    switch (view) {
        case 'poly':
            return 'Polystore';
        case 'postgres':
            return 'PostgreSQL';
        case 'neo4j':
            return 'Neo4j';
        default:
            return assertNever(view);
    }
}

/** Whether the view needs GET /schema/mapping for every collection. */
export function needsMappings(view: StoreView): boolean {
    switch (view) {
        case 'poly':
            return false;
        case 'postgres':
        case 'neo4j':
            return true;
        default:
            return assertNever(view);
    }
}

/** `mappings` may be null only for views where needsMappings() is false. */
export function buildStoreGraph(
    view: StoreView,
    schema: DatabaseSchema,
    mappings: Mappings | null,
): StoreGraph {
    switch (view) {
        case 'poly':
            return buildPolyGraph(schema);
        case 'postgres':
            return buildPostgresGraph(schema, requireMappings(view, mappings));
        case 'neo4j':
            return buildNeo4jGraph(schema, requireMappings(view, mappings));
        default:
            return assertNever(view);
    }
}

function requireMappings(view: StoreView, mappings: Mappings | null): Mappings {
    if (!mappings) throw new Error(`the ${view} view needs the storage mappings`);
    return mappings;
}
