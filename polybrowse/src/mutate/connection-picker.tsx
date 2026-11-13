/**
 * Add a connection from one instance to another.
 *
 * The far-side instance is picked from a searchable list (one query for the
 * target collection — demo-sized data, filtered client-side), and the
 * connection's own data is entered from its connectionDataSchema.
 *
 * Either side may be collection1: the server asserts the pair matches the
 * ConnectionModel in some order and DatabaseManager.insertConnection
 * normalizes, so this always sends "this instance" first.
 */
import { useMemo, useState } from 'react';
import { Link2, Search } from 'lucide-react';
import { useInsertConnection, useQueryResult } from '@/api/hooks';
import type { ConnectionModel, PolyInputValue, PolyRow } from '@/api/types';
import { rootPath, toRequest } from '@/query/path';
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
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { FieldInput } from './field-input';
import { emptyValueFor } from './values';

export interface ConnectionPickerProps {
    readonly connection: ConnectionModel;
    readonly thisCollection: string;
    readonly thisInstanceId: string;
    readonly otherCollection: string;
}

export function ConnectionPicker(props: ConnectionPickerProps) {
    const [isOpen, setIsOpen] = useState(false);

    return (
        <Dialog open={isOpen} onOpenChange={setIsOpen}>
            <DialogTrigger asChild>
                <Button variant="ghost" size="icon" className="size-6" title="Add a connection">
                    <Link2 className="size-3" />
                </Button>
            </DialogTrigger>
            {isOpen && <PickerForm {...props} onDone={() => setIsOpen(false)} />}
        </Dialog>
    );
}

interface PickerFormProps extends ConnectionPickerProps {
    readonly onDone: () => void;
}

function PickerForm({
    connection,
    thisCollection,
    thisInstanceId,
    otherCollection,
    onDone,
}: PickerFormProps) {
    const request = useMemo(() => toRequest(rootPath(otherCollection)), [otherCollection]);
    const candidatesQuery = useQueryResult(request);
    const rows = candidatesQuery.data?.data.type === 'documents' ? candidatesQuery.data.data.data : [];

    const [search, setSearch] = useState('');
    const [targetId, setTargetId] = useState<string | null>(null);

    const dataFields = Object.entries(connection.connectionDataSchema);
    const [values, setValues] = useState<Record<string, PolyInputValue>>(() =>
        Object.fromEntries(dataFields.map(([field, type]) => [field, emptyValueFor(type)])),
    );
    const [invalidFields, setInvalidFields] = useState<readonly string[]>([]);
    const insert = useInsertConnection(connection.connectionDataSchema);

    const matches =
        search === ''
            ? rows
            : rows.filter((row) => describeRow(row, otherCollection).toLowerCase().includes(search.toLowerCase()));

    return (
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-md">
            <DialogHeader>
                <DialogTitle className="text-base">
                    Connect via <span className="font-mono">{connection.name}</span>
                </DialogTitle>
                <DialogDescription className="text-xs">
                    Links this <span className="font-mono">{thisCollection}</span> instance to a{' '}
                    <span className="font-mono">{otherCollection}</span> instance.
                </DialogDescription>
            </DialogHeader>

            <div className="space-y-3">
                <div className="space-y-1">
                    <Label className="text-xs">Target {otherCollection}</Label>
                    <div className="relative">
                        <Search className="text-muted-foreground absolute top-2.5 left-2 size-3" />
                        <Input
                            value={search}
                            onChange={(event) => setSearch(event.target.value)}
                            placeholder="Search"
                            className="h-8 pl-7 text-xs"
                        />
                    </div>
                    <div className="max-h-40 space-y-1 overflow-y-auto rounded-md border p-1">
                        {candidatesQuery.isPending && <Skeleton className="h-6 w-full" />}
                        {matches.length === 0 && !candidatesQuery.isPending && (
                            <p className="text-muted-foreground p-1 text-xs">No candidates.</p>
                        )}
                        {matches.map((row, index) => {
                            const id = row[otherCollection]?.['_id'];
                            if (typeof id !== 'string') return null;
                            return (
                                <button
                                    key={index}
                                    type="button"
                                    onClick={() => setTargetId(id)}
                                    className={cn(
                                        'block w-full truncate rounded px-1.5 py-1 text-left font-mono text-xs',
                                        targetId === id ? 'bg-primary/15' : 'hover:bg-accent',
                                    )}
                                >
                                    {describeRow(row, otherCollection)}
                                </button>
                            );
                        })}
                    </div>
                </div>

                {dataFields.length > 0 && (
                    <div className="space-y-2">
                        <Label className="text-xs">Connection data</Label>
                        {dataFields.map(([field, type]) => (
                            <FieldInput
                                key={field}
                                field={field}
                                type={type}
                                value={values[field] ?? emptyValueFor(type)}
                                onChange={(value) =>
                                    setValues((current) => ({ ...current, [field]: value }))
                                }
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
                    </div>
                )}

                {insert.isError && (
                    <p className="text-destructive text-xs break-words">
                        {insert.error instanceof Error ? insert.error.message : 'insert failed'}
                    </p>
                )}
            </div>

            <DialogFooter>
                <Button variant="ghost" size="sm" onClick={onDone}>
                    Cancel
                </Button>
                <Button
                    size="sm"
                    disabled={targetId === null || invalidFields.length > 0 || insert.isPending}
                    onClick={() => {
                        if (!targetId) return;
                        insert.mutate(
                            {
                                connection: connection.name,
                                collection1Name: thisCollection,
                                collection1Uuid: thisInstanceId,
                                collection2Name: otherCollection,
                                collection2Uuid: targetId,
                                fields: values,
                            },
                            { onSuccess: onDone },
                        );
                    }}
                >
                    {insert.isPending ? 'Connecting…' : 'Connect'}
                </Button>
            </DialogFooter>
        </DialogContent>
    );
}

/** A short label for a candidate row: its non-id fields, falling back to the id. */
function describeRow(row: PolyRow, segment: string): string {
    const fields = row[segment] ?? {};
    const described = Object.entries(fields)
        .filter(([field]) => field !== '_id')
        .map(([field, value]) => `${field}=${value ?? 'null'}`)
        .join(' ');
    return described === '' ? String(fields['_id'] ?? '') : described;
}
