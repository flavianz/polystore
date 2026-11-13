/**
 * Filters, and their serialization to the server's condition DSL.
 *
 * The server does NOT accept JSON filter objects. Each segment carries a
 * free-text condition string that server/ConditionParser.kt parses. Its grammar:
 *
 *   field == v | field != v | field < n | field > n | field in [v, ...]
 *   combined with && , || , ! and parentheses
 *   values: 'str' | "str" | true | false | null | int | float | bare-UUID
 *
 * Deliberately NOT supported by the server (do not emit): <= and >= — the
 * tokenizer recognizes them but parseComparison() rejects them.
 *
 * We model a filter as clauses ANDed together, which is what the type-aware
 * editor needs, plus a `raw` escape hatch for DSL typed by hand in the raw
 * query panel.
 */
import { assertNever } from '@/lib/assert-never';

/** A UUID value, tagged so it is emitted bare rather than quoted. */
export interface UuidValue {
    readonly uuid: string;
}

export type ClauseValue = string | number | boolean | null | UuidValue;

export type Clause =
    | { readonly op: 'eq'; readonly field: string; readonly value: ClauseValue }
    | { readonly op: 'neq'; readonly field: string; readonly value: ClauseValue }
    | { readonly op: 'lt'; readonly field: string; readonly value: number }
    | { readonly op: 'gt'; readonly field: string; readonly value: number }
    | { readonly op: 'in'; readonly field: string; readonly values: readonly ClauseValue[] };

/**
 * A segment's filter: either structured clauses (ANDed) or raw DSL the user
 * typed. `raw` wins when present, so hand-written DSL is never silently lost.
 */
export interface Filter {
    readonly clauses: readonly Clause[];
    readonly raw?: string;
}

export const EMPTY_FILTER: Filter = { clauses: [] };

export function isFilterEmpty(filter: Filter | null | undefined): boolean {
    if (!filter) return true;
    if (filter.raw !== undefined && filter.raw.trim() !== '') return false;
    return filter.clauses.length === 0;
}

const IDENTIFIER = /^[a-zA-Z_][a-zA-Z0-9_]*$/;

function serializeValue(value: ClauseValue): string {
    if (value === null) return 'null';
    switch (typeof value) {
        case 'boolean':
            return value ? 'true' : 'false';
        case 'number':
            if (!Number.isFinite(value)) {
                throw new Error(`cannot serialize non-finite number: ${value}`);
            }
            return String(value);
        case 'string':
            return quote(value);
        case 'object':
            // UUIDs are emitted bare — the tokenizer matches the 36-char pattern.
            if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value.uuid)) {
                throw new Error(`not a UUID: ${value.uuid}`);
            }
            return value.uuid;
        default:
            throw new Error(`cannot serialize value of type ${typeof value}`);
    }
}

/**
 * The server's tokenizer has no escape sequences: a single-quoted token is
 * `'[^']*'` and a double-quoted one `"[^"]*"`. So we pick whichever quote the
 * string does not contain, and reject strings containing both.
 */
function quote(value: string): string {
    const hasSingle = value.includes("'");
    const hasDouble = value.includes('"');
    if (hasSingle && hasDouble) {
        throw new Error(
            'the condition DSL cannot express a string containing both quote characters',
        );
    }
    return hasSingle ? `"${value}"` : `'${value}'`;
}

function serializeClause(clause: Clause): string {
    if (!IDENTIFIER.test(clause.field)) {
        throw new Error(`not a valid field name: ${clause.field}`);
    }
    switch (clause.op) {
        case 'eq':
            return `${clause.field} == ${serializeValue(clause.value)}`;
        case 'neq':
            return `${clause.field} != ${serializeValue(clause.value)}`;
        case 'lt':
            return `${clause.field} < ${serializeValue(clause.value)}`;
        case 'gt':
            return `${clause.field} > ${serializeValue(clause.value)}`;
        case 'in':
            return `${clause.field} in [${clause.values.map(serializeValue).join(', ')}]`;
        default:
            return assertNever(clause);
    }
}

/** Serializes a filter to the condition DSL, or null when it filters nothing. */
export function filterToDsl(filter: Filter | null | undefined): string | null {
    if (!filter) return null;
    if (filter.raw !== undefined) {
        const trimmed = filter.raw.trim();
        return trimmed === '' ? null : trimmed;
    }
    if (filter.clauses.length === 0) return null;
    // Parenthesized so that a clause is never re-associated by the server's
    // precedence rules (|| binds looser than &&, but raw clauses may contain neither).
    return filter.clauses.map(serializeClause).join(' && ');
}

/** Short human-readable form for filter badges in the path bar. */
export function describeClause(clause: Clause): string {
    const symbols = { eq: '=', neq: '≠', lt: '<', gt: '>' } as const;
    if (clause.op === 'in') {
        return `${clause.field} ∈ [${clause.values.map(describeValue).join(', ')}]`;
    }
    return `${clause.field} ${symbols[clause.op]} ${describeValue(clause.value)}`;
}

function describeValue(value: ClauseValue): string {
    if (value === null) return 'null';
    if (typeof value === 'object') return `${value.uuid.slice(0, 8)}…`;
    return String(value);
}
