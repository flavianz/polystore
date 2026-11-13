/**
 * Insert an instance, with the form generated from the collection's PolySchema.
 *
 * DatabaseManager.insertDocument requires every schema field to be present and
 * type-correct, and requires parentDocUuid exactly when the collection has a
 * parent — so a subcollection can only be inserted into from an instance of its
 * parent, which is why this is opened from the inspector in that case.
 */
import { useState } from 'react';
import { Plus } from 'lucide-react';
import { useInsertDocument } from '@/api/hooks';
import type { CollectionModel, PolyInputValue } from '@/api/types';
import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog';
import { FieldInput } from './field-input';
import { emptyValueFor } from './values';

export interface InsertInstanceDialogProps {
    readonly collection: CollectionModel;
    /** The parent instance's id — required iff the collection has a parent. */
    readonly parentDocUuid: string | null;
    readonly trigger?: React.ReactNode;
    readonly onInserted?: (id: string) => void;
}

export function InsertInstanceDialog({
    collection,
    parentDocUuid,
    trigger,
    onInserted,
}: InsertInstanceDialogProps) {
    const [isOpen, setIsOpen] = useState(false);

    return (
        <Dialog open={isOpen} onOpenChange={setIsOpen}>
            <DialogTrigger asChild>
                {trigger ?? (
                    <Button variant="outline" size="sm" className="h-7 text-xs">
                        <Plus className="mr-1 size-3" />
                        Add {collection.name}
                    </Button>
                )}
            </DialogTrigger>
            {/* Remounted per opening so the form always starts empty. */}
            {isOpen && (
                <InsertForm
                    collection={collection}
                    parentDocUuid={parentDocUuid}
                    onDone={(id) => {
                        setIsOpen(false);
                        if (id) onInserted?.(id);
                    }}
                />
            )}
        </Dialog>
    );
}

interface InsertFormProps {
    readonly collection: CollectionModel;
    readonly parentDocUuid: string | null;
    readonly onDone: (id?: string) => void;
}

function InsertForm({ collection, parentDocUuid, onDone }: InsertFormProps) {
    const fields = Object.entries(collection.schema);

    const [values, setValues] = useState<Record<string, PolyInputValue>>(() =>
        Object.fromEntries(fields.map(([field, type]) => [field, emptyValueFor(type)])),
    );
    const [invalidFields, setInvalidFields] = useState<readonly string[]>([]);
    const insert = useInsertDocument();

    const needsParent = collection.parentCollection !== null;
    const isMissingParent = needsParent && parentDocUuid === null;
    const canSubmit = invalidFields.length === 0 && !isMissingParent && !insert.isPending;

    return (
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-md">
            <DialogHeader>
                <DialogTitle className="text-base">
                    Add to <span className="font-mono">{collection.name}</span>
                </DialogTitle>
                <DialogDescription className="text-xs">
                    {needsParent ? (
                        <>
                            Subcollection of{' '}
                            <span className="font-mono">{collection.parentCollection}</span>; the
                            new instance is owned by the selected parent instance.
                        </>
                    ) : (
                        'The instance is written to PostgreSQL, MongoDB and Neo4j.'
                    )}
                </DialogDescription>
            </DialogHeader>

            <div className="space-y-3">
                {fields.length === 0 && (
                    <p className="text-muted-foreground text-xs">
                        This collection has no fields; the instance will only have an id.
                    </p>
                )}
                {fields.map(([field, type]) => (
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

                {isMissingParent && (
                    <p className="text-destructive text-xs">
                        Select an instance of {collection.parentCollection} first.
                    </p>
                )}
                {insert.isError && (
                    <p className="text-destructive text-xs break-words">
                        {insert.error instanceof Error ? insert.error.message : 'insert failed'}
                    </p>
                )}
            </div>

            <DialogFooter>
                <Button variant="ghost" size="sm" onClick={() => onDone()}>
                    Cancel
                </Button>
                <Button
                    size="sm"
                    disabled={!canSubmit}
                    onClick={() => {
                        insert.mutate(
                            { collection: collection.name, fields: values, parentDocUuid },
                            { onSuccess: (id) => onDone(id) },
                        );
                    }}
                >
                    {insert.isPending ? 'Inserting…' : 'Insert'}
                </Button>
            </DialogFooter>
        </DialogContent>
    );
}
