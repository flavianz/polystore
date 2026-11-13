/**
 * The store views and the dispatch to their graph builders.
 */
import type { DatabaseSchema } from '@/api/types';
import { assertNever } from '@/lib/assert-never';
import { buildConfigGraph, type ConfigStore } from './config-graph';
import type { Mappings, StoreGraph } from './graph-model';
import { buildMongoGraph } from './mongo-graph';
import { buildNeo4jGraph } from './neo4j-graph';
import { buildPolyGraph } from './poly-graph';
import { buildPostgresGraph } from './postgres-graph';

export const STORE_VIEWS = ['poly', 'postgres', 'mongo', 'neo4j'] as const;
export type StoreView = (typeof STORE_VIEWS)[number];

export function storeViewLabel(view: StoreView): string {
    switch (view) {
        case 'poly':
            return 'Polystore';
        case 'postgres':
            return 'PostgreSQL';
        case 'mongo':
            return 'MongoDB';
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
        case 'mongo':
        case 'neo4j':
            return true;
        default:
            return assertNever(view);
    }
}

/** The store whose schema-registry metadata belongs in this view, if any. */
export function configStoreOf(view: StoreView): ConfigStore | null {
    switch (view) {
        case 'poly':
            return null;
        case 'postgres':
        case 'mongo':
        case 'neo4j':
            return view;
        default:
            return assertNever(view);
    }
}

/**
 * `mappings` may be null only for views where needsMappings() is false.
 * `includeConfig` adds the store's schema-registry tables/collections/labels.
 */
export function buildStoreGraph(
    view: StoreView,
    schema: DatabaseSchema,
    mappings: Mappings | null,
    includeConfig = false,
): StoreGraph {
    const graph = buildDataGraph(view, schema, mappings);
    const configStore = includeConfig ? configStoreOf(view) : null;
    if (!configStore) return graph;
    const config = buildConfigGraph(configStore);
    return { nodes: [...graph.nodes, ...config.nodes], edges: [...graph.edges, ...config.edges] };
}

function buildDataGraph(
    view: StoreView,
    schema: DatabaseSchema,
    mappings: Mappings | null,
): StoreGraph {
    switch (view) {
        case 'poly':
            return buildPolyGraph(schema);
        case 'postgres':
            return buildPostgresGraph(schema, requireMappings(view, mappings));
        case 'mongo':
            return buildMongoGraph(schema, requireMappings(view, mappings));
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
