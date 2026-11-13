/**
 * Which operators and value shapes are legal for a given DataType.
 *
 * This is where the filter editor becomes type-aware: the server's parser will
 * happily tokenize `name > 5`, but PostgresDriver would then compare a TEXT
 * column to a number, and DatabaseManager.validateConditionFields rejects a
 * value whose type does not match the field. So the editor only offers
 * operators that make sense for the field's DataType.
 *
 * Note that `<` and `>` are numeric-only on the server: Condition.GreaterThan /
 * LessThan take a Number, and ConditionParser.consumeNumberValue rejects
 * anything else.
 */
import type { DataType } from '@/api/types';
import type { Clause, ClauseValue } from './condition';

export type ClauseOp = Clause['op'];

export const OP_LABELS: Record<ClauseOp, string> = {
    eq: 'equals',
    neq: 'not equals',
    lt: 'less than',
    gt: 'greater than',
    in: 'one of',
};

/** The synthetic id field every instance has; it is always a UUID. */
export const ID_FIELD = '_id';

export function operatorsFor(type: DataType | null): readonly ClauseOp[] {
    switch (type) {
        case 'INT':
        case 'FLOAT':
            return ['eq', 'neq', 'gt', 'lt', 'in'];
        case 'STRING':
            return ['eq', 'neq', 'in'];
        case 'UUID':
            return ['eq', 'neq', 'in'];
        case 'BOOLEAN':
            return ['eq', 'neq'];
        case 'NULL':
            return ['eq', 'neq'];
        // null = the _id field or a dynamic (non-schema) field: allow everything
        // and let the server validate.
        case null:
            return ['eq', 'neq', 'gt', 'lt', 'in'];
        default:
            return ['eq', 'neq'];
    }
}

/** A sensible starting value when a clause is created or its field changes. */
export function defaultValueFor(type: DataType | null): ClauseValue {
    switch (type) {
        case 'INT':
        case 'FLOAT':
            return 0;
        case 'BOOLEAN':
            return true;
        case 'UUID':
            return { uuid: '' };
        case 'NULL':
            return null;
        default:
            return '';
    }
}

export const UUID_PATTERN =
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * Parses what the user typed into a value of the field's type.
 * Returns an error message instead of a value when it cannot be represented.
 */
export function parseValueInput(
    raw: string,
    type: DataType | null,
): { readonly value: ClauseValue } | { readonly error: string } {
    const trimmed = raw.trim();

    switch (type) {
        case 'INT': {
            if (!/^-?\d+$/.test(trimmed)) return { error: 'expected a whole number' };
            return { value: Number(trimmed) };
        }
        case 'FLOAT': {
            const parsed = Number(trimmed);
            if (trimmed === '' || !Number.isFinite(parsed)) return { error: 'expected a number' };
            // Neo4jDriver.toPolyValue uses Value.asFloat(), which throws
            // LossyCoercion for doubles that are not float-exact.
            if (!isFloatExact(parsed)) {
                return { error: 'not representable as a 32-bit float (Neo4j rejects it)' };
            }
            return { value: parsed };
        }
        case 'BOOLEAN': {
            if (trimmed === 'true') return { value: true };
            if (trimmed === 'false') return { value: false };
            return { error: 'expected true or false' };
        }
        case 'UUID': {
            if (!UUID_PATTERN.test(trimmed)) return { error: 'expected a UUID' };
            return { value: { uuid: trimmed } };
        }
        case 'STRING':
            // The DSL tokenizer has no escapes, so a string cannot contain both
            // quote characters (condition.ts enforces this on serialization).
            if (raw.includes("'") && raw.includes('"')) {
                return { error: 'cannot contain both \' and "' };
            }
            return { value: raw };
        case 'NULL':
            return { value: null };
        default: {
            // Unknown field type: guess from the input, preferring a UUID so
            // that _id filters work.
            if (UUID_PATTERN.test(trimmed)) return { value: { uuid: trimmed } };
            if (trimmed === 'true') return { value: true };
            if (trimmed === 'false') return { value: false };
            if (trimmed === 'null') return { value: null };
            if (trimmed !== '' && Number.isFinite(Number(trimmed))) {
                return { value: Number(trimmed) };
            }
            return { value: raw };
        }
    }
}

/** True when the double survives a round trip through a 32-bit float. */
function isFloatExact(value: number): boolean {
    return Math.fround(value) === value;
}

/** Renders a value back into the text an input should show. */
export function valueToInput(value: ClauseValue): string {
    if (value === null) return '';
    if (typeof value === 'object') return value.uuid;
    return String(value);
}

/** Numeric operators need a number, which the clause types already demand. */
export function isNumericOp(op: ClauseOp): op is 'lt' | 'gt' {
    return op === 'lt' || op === 'gt';
}
