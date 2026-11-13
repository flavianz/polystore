/**
 * URL (de)serialization of the query path.
 *
 * The path lives in the URL so that back/forward navigation and shareable
 * links work for free. We store the *structured* steps (not the flattened wire
 * segments) so that typed filter clauses survive a reload and the filter
 * editor can still render chips rather than raw DSL.
 *
 * Decoding treats the URL as untrusted input and returns null on anything
 * malformed instead of throwing.
 */
import type { Clause, ClauseValue, Filter } from './condition';
import type { PathStep, QueryPath } from './path';

const PARAM = 'path';

export function encodePath(path: QueryPath): string {
    const params = new URLSearchParams();
    params.set(PARAM, JSON.stringify(path));
    return `?${params.toString()}`;
}

export function decodePath(search: string): QueryPath | null {
    const raw = new URLSearchParams(search).get(PARAM);
    if (!raw) return null;

    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return null;
    }
    if (!Array.isArray(parsed) || parsed.length === 0) return null;

    const steps: PathStep[] = [];
    for (const candidate of parsed) {
        const step = parseStep(candidate);
        if (!step) return null;
        steps.push(step);
    }
    // The server requires a collection first; reject anything else.
    if (steps[0]?.kind !== 'collection') return null;
    return steps;
}

function parseStep(value: unknown): PathStep | null {
    if (!isRecord(value)) return null;

    if (value.kind === 'collection') {
        const name = asName(value.name);
        const filter = parseFilter(value.filter);
        if (name === null || !filter) return null;
        return { kind: 'collection', name, filter, only: parseOnly(value.only) };
    }

    if (value.kind === 'connection') {
        const connectionName = asName(value.connectionName);
        const collectionName = asName(value.collectionName);
        const connectionFilter = parseFilter(value.connectionFilter);
        const collectionFilter = parseFilter(value.collectionFilter);
        if (connectionName === null || collectionName === null) return null;
        if (!connectionFilter || !collectionFilter) return null;
        return {
            kind: 'connection',
            connectionName,
            connectionFilter,
            connectionOnly: parseOnly(value.connectionOnly),
            collectionName,
            collectionFilter,
            collectionOnly: parseOnly(value.collectionOnly),
        };
    }

    return null;
}

function parseFilter(value: unknown): Filter | null {
    if (value === undefined || value === null) return { clauses: [] };
    if (!isRecord(value)) return null;

    const clauses: Clause[] = [];
    if (value.clauses !== undefined) {
        if (!Array.isArray(value.clauses)) return null;
        for (const candidate of value.clauses) {
            const clause = parseClause(candidate);
            if (!clause) return null;
            clauses.push(clause);
        }
    }

    if (value.raw === undefined) return { clauses };
    if (typeof value.raw !== 'string') return null;
    return { clauses, raw: value.raw };
}

function parseClause(value: unknown): Clause | null {
    if (!isRecord(value)) return null;
    const field = asName(value.field);
    if (field === null) return null;

    switch (value.op) {
        case 'eq':
        case 'neq': {
            const parsedValue = parseClauseValue(value.value);
            return parsedValue === undefined ? null : { op: value.op, field, value: parsedValue };
        }
        case 'lt':
        case 'gt':
            return typeof value.value === 'number' && Number.isFinite(value.value)
                ? { op: value.op, field, value: value.value }
                : null;
        case 'in': {
            if (!Array.isArray(value.values)) return null;
            const values: ClauseValue[] = [];
            for (const candidate of value.values) {
                const parsedValue = parseClauseValue(candidate);
                if (parsedValue === undefined) return null;
                values.push(parsedValue);
            }
            return { op: 'in', field, values };
        }
        default:
            return null;
    }
}

/** Returns undefined (not null) for "invalid", since null is a legal value. */
function parseClauseValue(value: unknown): ClauseValue | undefined {
    if (value === null) return null;
    switch (typeof value) {
        case 'string':
        case 'boolean':
            return value;
        case 'number':
            return Number.isFinite(value) ? value : undefined;
        case 'object':
            return isRecord(value) && typeof value.uuid === 'string'
                ? { uuid: value.uuid }
                : undefined;
        default:
            return undefined;
    }
}

function parseOnly(value: unknown): readonly string[] | null {
    if (value === undefined || value === null) return null;
    if (!Array.isArray(value)) return null;
    return value.every((entry) => typeof entry === 'string') ? (value as string[]) : null;
}

function asName(value: unknown): string | null {
    return typeof value === 'string' && value !== '' ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
