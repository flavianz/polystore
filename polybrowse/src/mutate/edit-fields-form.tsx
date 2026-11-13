/**
 * Edit the scalar fields of one instance (POST /document/update).
 *
 * Only schema fields are editable: PostgresDriver.updateDocument has an open
 * TODO for dynamic data, so a non-schema field would be written in Mongo and
 * Neo4j but silently dropped in PostgreSQL. `_id` is not editable either.
 */
import { useState } from 'react';
import { Check, Pencil, X } from 'lucide-react';
import { useUpdateDocument } from '@/api/hooks';
import type { CollectionModel, PolyFieldValue, PolyInputValue } from '@/api/types';
import { Button } from '@/components/ui/button';
import { FieldInput } from './field-input';
import { emptyValueFor, resultValueToInput } from './values';

export interface EditFieldsFormProps {
    readonly collection: CollectionModel;
    readonly instanceId: string;
    readonly fields: Record<string, PolyFieldValue>;
}

export function EditFieldsForm({ collection, instanceId, fields }: EditFieldsFormProps) {
    const [isEditing, setIsEditing] = useState(false);

    if (!isEditing) {
        return (
            <Button
                variant="outline"
                size="sm"
                className="h-7 w-full text-xs"
                onClick={() => setIsEditing(true)}
                disabled={Object.keys(collection.schema).length === 0}
            >
                <Pencil className="mr-1 size-3" />
                Edit fields
            </Button>
        );
    }

    return (
        <EditForm
            collection={collection}
            instanceId={instanceId}
            fields={fields}
            onDone={() => setIsEditing(false)}
        />
    );
}

interface EditFormProps extends EditFieldsFormProps {
    readonly onDone: () => void;
}

function EditForm({ collection, instanceId, fields, onDone }: EditFormProps) {
    const schemaFields = Object.entries(collection.schema);

    const [values, setValues] = useState<Record<string, PolyInputValue>>(() =>
        Object.fromEntries(
            schemaFields.map(([field, type]) => [
                field,
                resultValueToInput(fields[field], type),
            ]),
        ),
    );
    const [invalidFields, setInvalidFields] = useState<readonly string[]>([]);
    const update = useUpdateDocument();

    return (
        <div className="space-y-3 rounded-md border p-2">
            {schemaFields.map(([field, type]) => (
                <FieldInput
                    key={field}
                    field={field}
                    type={type}
                    value={values[field] ?? emptyValueFor(type)}
                    onChange={(value) => setValues((current) => ({ ...current, [field]: value }))}
                    onValidityChange={(isValid) =>
                        setInvalidFields((current) =>
                            isValid
                                ? current.filter((entry) => entry !== field)
                                : current.includes(field)
                                  ? current
                                  : [...current, field],
                        )
                    }
                />
            ))}

            {update.isError && (
                <p className="text-destructive text-xs break-words">
                    {update.error instanceof Error ? update.error.message : 'update failed'}
                </p>
            )}

            <div className="flex justify-end gap-1">
                <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={onDone}>
                    <X className="mr-1 size-3" />
                    Cancel
                </Button>
                <Button
                    size="sm"
                    className="h-7 text-xs"
                    disabled={invalidFields.length > 0 || update.isPending}
                    onClick={() =>
                        update.mutate(
                            { collection: collection.name, uuid: instanceId, fields: values },
                            { onSuccess: onDone },
                        )
                    }
                >
                    <Check className="mr-1 size-3" />
                    {update.isPending ? 'Saving…' : 'Save'}
                </Button>
            </div>
        </div>
    );
}
