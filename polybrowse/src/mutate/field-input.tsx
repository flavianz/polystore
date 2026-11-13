/**
 * A single typed field input, generated from a field's DataType.
 *
 * Shared by the insert and edit forms. Validation reuses parseValueInput from
 * query/operators, which already encodes the server's constraints (including
 * that a FLOAT must be float-exact, or Neo4j's reader throws LossyCoercion).
 */
import { useState } from 'react';
import type { DataType, PolyInputValue } from '@/api/types';
import { parseValueInput } from '@/query/operators';
import { clauseValueToInput, inputText } from './values';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';

export interface FieldInputProps {
    readonly field: string;
    readonly type: DataType;
    readonly value: PolyInputValue;
    readonly onChange: (value: PolyInputValue) => void;
    readonly onValidityChange: (isValid: boolean) => void;
}

export function FieldInput({
    field,
    type,
    value,
    onChange,
    onValidityChange,
}: FieldInputProps) {
    const [draft, setDraft] = useState(() => inputText(value));

    if (type === 'BOOLEAN') {
        return (
            <div className="space-y-1">
                <Label className="text-xs">
                    {field} <TypeHint type={type} />
                </Label>
                <Select
                    value={value === true ? 'true' : 'false'}
                    onValueChange={(raw) => {
                        onChange(raw === 'true');
                        onValidityChange(true);
                    }}
                >
                    <SelectTrigger className="h-8 text-xs">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        <SelectItem value="true" className="text-xs">
                            true
                        </SelectItem>
                        <SelectItem value="false" className="text-xs">
                            false
                        </SelectItem>
                    </SelectContent>
                </Select>
            </div>
        );
    }

    const parsed = parseValueInput(draft, type);
    const error = 'error' in parsed ? parsed.error : null;

    return (
        <div className="space-y-1">
            <Label className="text-xs">
                {field} <TypeHint type={type} />
            </Label>
            <Input
                value={draft}
                inputMode={type === 'INT' || type === 'FLOAT' ? 'decimal' : undefined}
                onChange={(event) => {
                    const next = event.target.value;
                    setDraft(next);
                    const result = parseValueInput(next, type);
                    if ('value' in result) {
                        onChange(clauseValueToInput(result.value));
                        onValidityChange(true);
                    } else {
                        onValidityChange(false);
                    }
                }}
                className="h-8 text-xs"
                aria-invalid={error !== null}
                spellCheck={false}
            />
            {error && <p className="text-destructive text-xs">{error}</p>}
        </div>
    );
}

function TypeHint({ type }: { readonly type: DataType }) {
    return <span className="text-muted-foreground font-mono">{type}</span>;
}

