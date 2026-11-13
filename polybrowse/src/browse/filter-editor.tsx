/**
 * Filter editor for one path step.
 *
 * A collection step has one filter; a connection step has two (the connection's
 * own data, and the collection on the far side), which is exactly the pair of
 * conditions QuerySegment.Connection carries in Kotlin.
 */
import { useState } from 'react';
import { Plus, Trash2, X } from 'lucide-react';
import type { DatabaseSchema, DataType, PolySchema } from '@/api/types';
import { assertNever } from '@/lib/assert-never';
import type { Clause, ClauseValue, Filter } from '@/query/condition';
import { filterToDsl } from '@/query/condition';
import {
    defaultValueFor,
    ID_FIELD,
    OP_LABELS,
    operatorsFor,
    parseValueInput,
    valueToInput,
    type ClauseOp,
} from '@/query/operators';
import type { PathStep } from '@/query/path';
import { collectionByName, connectionByName } from '@/schema/model';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';

export interface FilterEditorProps {
    readonly schema: DatabaseSchema;
    readonly step: PathStep;
    readonly onChange: (step: PathStep) => void;
    readonly onClose: () => void;
}

export function FilterEditor({ schema, step, onChange, onClose }: FilterEditorProps) {
    return (
        <div className="bg-card space-y-4 rounded-md border p-3">
            <div className="flex items-center justify-between">
                <h2 className="text-sm font-medium">
                    Filters on{' '}
                    <span className="font-mono">
                        {step.kind === 'collection' ? step.name : step.collectionName}
                    </span>
                </h2>
                <Button variant="ghost" size="icon" className="size-6" onClick={onClose}>
                    <X className="size-3.5" />
                </Button>
            </div>

            {renderSections(schema, step, onChange)}
        </div>
    );
}

function renderSections(
    schema: DatabaseSchema,
    step: PathStep,
    onChange: (step: PathStep) => void,
) {
    switch (step.kind) {
        case 'collection': {
            const collection = collectionByName(schema, step.name);
            return (
                <FilterSection
                    label={`${step.name} fields`}
                    polySchema={collection?.schema ?? {}}
                    allowIdField
                    filter={step.filter}
                    onFilterChange={(filter) => onChange({ ...step, filter })}
                />
            );
        }
        case 'connection': {
            const connection = connectionByName(schema, step.connectionName);
            const collection = collectionByName(schema, step.collectionName);
            return (
                <div className="space-y-4">
                    <FilterSection
                        label={`${step.connectionName} connection data`}
                        polySchema={connection?.connectionDataSchema ?? {}}
                        // Connection rows have no _id: DatabaseManager validates
                        // connection conditions with allowIdField = false.
                        allowIdField={false}
                        filter={step.connectionFilter}
                        onFilterChange={(connectionFilter) =>
                            onChange({ ...step, connectionFilter })
                        }
                    />
                    <FilterSection
                        label={`${step.collectionName} fields`}
                        polySchema={collection?.schema ?? {}}
                        allowIdField
                        filter={step.collectionFilter}
                        onFilterChange={(collectionFilter) =>
                            onChange({ ...step, collectionFilter })
                        }
                    />
                </div>
            );
        }
        default:
            return assertNever(step);
    }
}

interface FilterSectionProps {
    readonly label: string;
    readonly polySchema: PolySchema;
    readonly allowIdField: boolean;
    readonly filter: Filter;
    readonly onFilterChange: (filter: Filter) => void;
}

function FilterSection({
    label,
    polySchema,
    allowIdField,
    filter,
    onFilterChange,
}: FilterSectionProps) {
    const fields = [...(allowIdField ? [ID_FIELD] : []), ...Object.keys(polySchema)];
    const isRawMode = filter.raw !== undefined;

    const typeOf = (field: string): DataType | null =>
        field === ID_FIELD ? 'UUID' : (polySchema[field] ?? null);

    const addClause = () => {
        // Prefer a real schema field: a fresh clause on _id would be invalid
        // until a UUID is typed into it.
        const field = Object.keys(polySchema)[0] ?? fields[0];
        if (!field) return;
        const type = typeOf(field);
        onFilterChange({
            ...filter,
            clauses: [...filter.clauses, { op: 'eq', field, value: defaultValueFor(type) }],
        });
    };

    return (
        <section className="space-y-2">
            <div className="flex items-center justify-between gap-2">
                <h3 className="text-muted-foreground text-xs font-medium tracking-wide uppercase">
                    {label}
                </h3>
                <Button
                    variant="ghost"
                    size="sm"
                    className="h-7 text-xs"
                    onClick={() =>
                        onFilterChange(
                            isRawMode
                                ? { clauses: filter.clauses }
                                : { clauses: filter.clauses, raw: filterToDsl(filter) ?? '' },
                        )
                    }
                >
                    {isRawMode ? 'Use editor' : 'Edit as DSL'}
                </Button>
            </div>

            {isRawMode ? (
                <div className="space-y-1">
                    <Textarea
                        value={filter.raw ?? ''}
                        onChange={(event) =>
                            onFilterChange({ ...filter, raw: event.target.value })
                        }
                        placeholder="age &gt; 30 &amp;&amp; status == 'open'"
                        className="font-mono text-xs"
                        rows={2}
                        spellCheck={false}
                    />
                    <p className="text-muted-foreground text-xs">
                        Operators: <code>== != &lt; &gt; in [..]</code> combined with{' '}
                        <code>&amp;&amp; || !</code> and parentheses. <code>&lt;=</code> and{' '}
                        <code>&gt;=</code> are not supported by the server.
                    </p>
                </div>
            ) : (
                <div className="space-y-2">
                    {filter.clauses.length === 0 && (
                        <p className="text-muted-foreground text-xs">No filters on this step.</p>
                    )}
                    {filter.clauses.map((clause, index) => (
                        <ClauseRow
                            key={index}
                            clause={clause}
                            fields={fields}
                            typeOf={typeOf}
                            onChange={(next) =>
                                onFilterChange({
                                    ...filter,
                                    clauses: filter.clauses.map((existing, i) =>
                                        i === index ? next : existing,
                                    ),
                                })
                            }
                            onRemove={() =>
                                onFilterChange({
                                    ...filter,
                                    clauses: filter.clauses.filter((_, i) => i !== index),
                                })
                            }
                        />
                    ))}
                    <Button
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs"
                        onClick={addClause}
                        disabled={fields.length === 0}
                    >
                        <Plus className="mr-1 size-3" />
                        Add filter
                    </Button>
                    {filter.clauses.length > 1 && (
                        <p className="text-muted-foreground text-xs">
                            Filters on a step are combined with <code>&amp;&amp;</code>. Two
                            filters on one numeric field give a range.
                        </p>
                    )}
                </div>
            )}
        </section>
    );
}

interface ClauseRowProps {
    readonly clause: Clause;
    readonly fields: readonly string[];
    readonly typeOf: (field: string) => DataType | null;
    readonly onChange: (clause: Clause) => void;
    readonly onRemove: () => void;
}

function ClauseRow({ clause, fields, typeOf, onChange, onRemove }: ClauseRowProps) {
    const type = typeOf(clause.field);
    const allowedOps = operatorsFor(type);

    return (
        <div className="flex flex-wrap items-start gap-2">
            <Select
                value={clause.field}
                onValueChange={(field) => {
                    const nextType = typeOf(field);
                    const ops = operatorsFor(nextType);
                    // Changing field may invalidate the operator and the value type.
                    const op = ops.includes(clause.op) ? clause.op : (ops[0] ?? 'eq');
                    onChange(rebuildClause(op, field, defaultValueFor(nextType)));
                }}
            >
                <SelectTrigger className="h-8 w-[9rem] text-xs">
                    <SelectValue />
                </SelectTrigger>
                <SelectContent>
                    {fields.map((field) => (
                        <SelectItem key={field} value={field} className="text-xs">
                            {field}
                            <span className="text-muted-foreground ml-2">
                                {typeOf(field) ?? ''}
                            </span>
                        </SelectItem>
                    ))}
                </SelectContent>
            </Select>

            <Select
                value={clause.op}
                onValueChange={(op) =>
                    onChange(rebuildClause(op as ClauseOp, clause.field, currentValue(clause)))
                }
            >
                <SelectTrigger className="h-8 w-[8rem] text-xs">
                    <SelectValue />
                </SelectTrigger>
                <SelectContent>
                    {allowedOps.map((op) => (
                        <SelectItem key={op} value={op} className="text-xs">
                            {OP_LABELS[op]}
                        </SelectItem>
                    ))}
                </SelectContent>
            </Select>

            <ValueInput clause={clause} type={type} onChange={onChange} />

            <Button variant="ghost" size="icon" className="size-8" onClick={onRemove}>
                <Trash2 className="size-3.5" />
            </Button>
        </div>
    );
}

/** The first value of a clause, used when switching operators. */
function currentValue(clause: Clause): ClauseValue {
    return clause.op === 'in' ? (clause.values[0] ?? '') : clause.value;
}

/**
 * Builds a clause of the requested operator. `lt`/`gt` are numeric-only in the
 * Kotlin model, so a non-numeric value collapses to 0 rather than being coerced.
 */
function rebuildClause(op: ClauseOp, field: string, value: ClauseValue): Clause {
    switch (op) {
        case 'eq':
        case 'neq':
            return { op, field, value };
        case 'lt':
        case 'gt':
            return { op, field, value: typeof value === 'number' ? value : 0 };
        case 'in':
            return { op, field, values: [value] };
        default:
            return assertNever(op);
    }
}

interface ValueInputProps {
    readonly clause: Clause;
    readonly type: DataType | null;
    readonly onChange: (clause: Clause) => void;
}

function ValueInput({ clause, type, onChange }: ValueInputProps) {
    if (clause.op === 'in') {
        return <ListValueInput clause={clause} type={type} onChange={onChange} />;
    }

    // Narrowed to eq/neq because those are the only operators a BOOLEAN field
    // offers (operatorsFor), and lt/gt are typed as numeric.
    if (type === 'BOOLEAN' && (clause.op === 'eq' || clause.op === 'neq')) {
        return (
            <Select
                value={clause.value === true ? 'true' : 'false'}
                onValueChange={(raw) => onChange({ ...clause, value: raw === 'true' })}
            >
                <SelectTrigger className="h-8 w-[7rem] text-xs">
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
        );
    }

    return (
        <SingleValueInput
            raw={valueToInput(clause.value)}
            type={type}
            numericOnly={clause.op === 'lt' || clause.op === 'gt'}
            onParsed={(value) => onChange(rebuildClause(clause.op, clause.field, value))}
        />
    );
}

interface SingleValueInputProps {
    readonly raw: string;
    readonly type: DataType | null;
    readonly numericOnly: boolean;
    readonly onParsed: (value: ClauseValue) => void;
}

/**
 * Holds the typed text locally and only pushes a parsed value upward. That lets
 * the user type "-" or "1." on the way to a number without an invalid value
 * ever entering the clause (where `lt`/`gt` are typed as numbers).
 */
function SingleValueInput({ raw, type, numericOnly, onParsed }: SingleValueInputProps) {
    // `lt`/`gt` force a numeric interpretation even on an untyped field.
    const effectiveType: DataType | null = numericOnly ? (type ?? 'FLOAT') : type;

    const [draft, setDraft] = useState(raw);
    const [syncedFrom, setSyncedFrom] = useState(raw);
    if (raw !== syncedFrom) {
        setSyncedFrom(raw);
        setDraft(raw);
    }

    const parsed = parseValueInput(draft, effectiveType);
    const error = 'error' in parsed ? parsed.error : null;

    return (
        <div className="space-y-1">
            <Input
                value={draft}
                inputMode={
                    effectiveType === 'INT' || effectiveType === 'FLOAT' ? 'decimal' : undefined
                }
                onChange={(event) => {
                    const next = event.target.value;
                    setDraft(next);
                    const result = parseValueInput(next, effectiveType);
                    if ('value' in result) onParsed(result.value);
                }}
                placeholder={placeholderFor(effectiveType)}
                className="h-8 w-[12rem] text-xs"
                aria-invalid={error !== null}
                spellCheck={false}
            />
            {error && <p className="text-destructive text-xs">{error}</p>}
        </div>
    );
}

interface ListValueInputProps {
    readonly clause: Extract<Clause, { op: 'in' }>;
    readonly type: DataType | null;
    readonly onChange: (clause: Clause) => void;
}

function ListValueInput({ clause, type, onChange }: ListValueInputProps) {
    const raw = clause.values.map(valueToInput).join(', ');

    return (
        <div className="space-y-1">
            <Input
                value={raw}
                onChange={(event) => {
                    const values = event.target.value
                        .split(',')
                        .map((entry) => entry.trim())
                        .filter((entry) => entry !== '')
                        .map((entry) => {
                            const parsed = parseValueInput(entry, type);
                            return 'value' in parsed ? parsed.value : entry;
                        });
                    onChange({ ...clause, values });
                }}
                placeholder="comma, separated, values"
                className="h-8 w-[16rem] text-xs"
                spellCheck={false}
            />
            <p className="text-muted-foreground text-xs">{clause.values.length} value(s)</p>
        </div>
    );
}

function placeholderFor(type: DataType | null): string {
    switch (type) {
        case 'INT':
            return '42';
        case 'FLOAT':
            return '9.5';
        case 'UUID':
            return '00000000-0000-0000-0000-000000000000';
        case 'STRING':
            return 'text';
        default:
            return 'value';
    }
}
