/**
 * Value helpers for the mutation forms. Kept out of the component files so
 * those only export components (fast refresh requires it).
 */
import type { DataType, PolyFieldValue, PolyInputValue } from '@/api/types';
import type { ClauseValue } from '@/query/condition';

/** A starting value for a field of this type, for a fresh insert form. */
export function emptyValueFor(type: DataType): PolyInputValue {
    switch (type) {
        case 'INT':
        case 'FLOAT':
            return 0;
        case 'BOOLEAN':
            return false;
        case 'NULL':
            return null;
        default:
            return '';
    }
}

/**
 * parseValueInput yields a ClauseValue, where a UUID is `{uuid}`; the write
 * side wants the server's tagged form `{type: "uuid", value}`.
 */
export function clauseValueToInput(value: ClauseValue): PolyInputValue {
    if (value !== null && typeof value === 'object') {
        return { type: 'uuid', value: value.uuid };
    }
    return value;
}

/** Result values arrive as plain JSON; re-tag UUIDs for the write side. */
export function resultValueToInput(
    value: PolyFieldValue | undefined,
    type: DataType,
): PolyInputValue {
    if (value === undefined || value === null) return null;
    if (type === 'UUID' && typeof value === 'string') return { type: 'uuid', value };
    return value;
}

/** The text an input should show for a value. */
export function inputText(value: PolyInputValue): string {
    if (value === null) return '';
    if (typeof value === 'object') return value.value;
    return String(value);
}
