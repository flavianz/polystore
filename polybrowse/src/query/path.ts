/**
 * The query path — the single piece of state every view renders.
 *
 * The wire format (server/RequestDefinitions.kt) is a flat list where a
 * `connection` segment must be immediately followed by the `collection`
 * segment it leads to. Rather than keep that invariant in a flat array and
 * hope, a connection step here *carries* its target collection, so an invalid
 * path cannot be constructed. `toRequest` flattens to the wire shape.
 */
import { assertNever } from '@/lib/assert-never';
import type { DriverType, QueryRequest, RequestQuerySegment } from '@/api/types';
import { EMPTY_FILTER, type Filter, filterToDsl } from './condition';

export interface CollectionStep {
    readonly kind: 'collection';
    readonly name: string;
    readonly filter: Filter;
    /** null = all fields. */
    readonly only: readonly string[] | null;
}

export interface ConnectionStep {
    readonly kind: 'connection';
    readonly connectionName: string;
    readonly connectionFilter: Filter;
    readonly connectionOnly: readonly string[] | null;
    /** The collection on the far side of the connection. */
    readonly collectionName: string;
    readonly collectionFilter: Filter;
    readonly collectionOnly: readonly string[] | null;
}

export type PathStep = CollectionStep | ConnectionStep;

/** Non-empty, and the first step is always a collection (enforced by the server). */
export type QueryPath = readonly PathStep[];

/** The collection a step lands on — mirrors QuerySegment.collectionName() in Kotlin. */
export function stepCollection(step: PathStep): string {
    switch (step.kind) {
        case 'collection':
            return step.name;
        case 'connection':
            return step.collectionName;
        default:
            return assertNever(step);
    }
}

/** The collection the path currently points at, i.e. what the result table shows. */
export function currentCollection(path: QueryPath): string | null {
    const last = path.at(-1);
    return last ? stepCollection(last) : null;
}

export function rootPath(collectionName: string): QueryPath {
    return [{ kind: 'collection', name: collectionName, filter: EMPTY_FILTER, only: null }];
}

/**
 * A path addressing exactly one instance, via `_id == <uuid>`.
 *
 * There is no get-by-id route: `_id` is a real column/key/property in every
 * store and DatabaseManager.validateConditionFields allows filtering a
 * collection on it, so this is how a single instance is addressed. It works
 * for subcollections too — a path may start at a child collection.
 */
export function instancePath(collectionName: string, id: string): QueryPath {
    return [
        {
            kind: 'collection',
            name: collectionName,
            filter: { clauses: [{ op: 'eq', field: '_id', value: { uuid: id } }] },
            only: null,
        },
    ];
}

/** Navigating into a subcollection appends a collection step. */
export function appendSubcollection(path: QueryPath, collectionName: string): QueryPath {
    return [
        ...path,
        { kind: 'collection', name: collectionName, filter: EMPTY_FILTER, only: null },
    ];
}

/** Navigating across a connection appends one step carrying both sides. */
export function appendConnection(
    path: QueryPath,
    connectionName: string,
    targetCollectionName: string,
): QueryPath {
    return [
        ...path,
        {
            kind: 'connection',
            connectionName,
            connectionFilter: EMPTY_FILTER,
            connectionOnly: null,
            collectionName: targetCollectionName,
            collectionFilter: EMPTY_FILTER,
            collectionOnly: null,
        },
    ];
}

/** Truncates the path so `index` becomes the last step (what "×" on a chip does). */
export function truncate(path: QueryPath, index: number): QueryPath {
    return path.slice(0, index + 1);
}

export function replaceStep(path: QueryPath, index: number, step: PathStep): QueryPath {
    return path.map((existing, i) => (i === index ? step : existing));
}

/** Flattens to the wire format of POST /query/get and /query/explain. */
export function toRequest(path: QueryPath, forceDriver?: DriverType | null): QueryRequest {
    const segments: RequestQuerySegment[] = [];

    for (const step of path) {
        switch (step.kind) {
            case 'collection':
                segments.push({
                    name: step.name,
                    type: 'collection',
                    condition: filterToDsl(step.filter),
                    only: step.only,
                });
                break;
            case 'connection':
                // Order matters: the server reads the connection segment, then
                // requires the next segment to be the target collection.
                segments.push({
                    name: step.connectionName,
                    type: 'connection',
                    condition: filterToDsl(step.connectionFilter),
                    only: step.connectionOnly,
                });
                segments.push({
                    name: step.collectionName,
                    type: 'collection',
                    condition: filterToDsl(step.collectionFilter),
                    only: step.collectionOnly,
                });
                break;
            default:
                return assertNever(step);
        }
    }

    return forceDriver ? { path: segments, forceDriver } : { path: segments };
}

/**
 * Parses the wire format back into steps, for the raw query panel. Returns null
 * if the segment sequence is not a valid path (so the panel can show an error
 * rather than sending something the server will reject).
 *
 * Conditions come back as raw DSL — we do not parse the DSL into clauses here.
 */
export function fromRequestSegments(
    segments: readonly RequestQuerySegment[],
): QueryPath | null {
    if (segments.length === 0) return null;
    if (segments[0]?.type !== 'collection') return null;

    const steps: PathStep[] = [];
    for (let i = 0; i < segments.length; i++) {
        const segment = segments[i];
        if (!segment) return null;

        if (segment.type === 'collection') {
            steps.push({
                kind: 'collection',
                name: segment.name,
                filter: toFilter(segment.condition),
                only: segment.only,
            });
            continue;
        }

        const target = segments[i + 1];
        if (!target || target.type !== 'collection') return null;
        steps.push({
            kind: 'connection',
            connectionName: segment.name,
            connectionFilter: toFilter(segment.condition),
            connectionOnly: segment.only,
            collectionName: target.name,
            collectionFilter: toFilter(target.condition),
            collectionOnly: target.only,
        });
        i++;
    }
    return steps;
}

function toFilter(condition: string | null): Filter {
    return condition === null || condition.trim() === ''
        ? EMPTY_FILTER
        : { clauses: [], raw: condition };
}
