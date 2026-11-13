/**
 * Inspector for the selected instance.
 *
 * Subcollections and connections load lazily, one query per expanded relation,
 * addressed as `<collection>[_id == uuid] › <relation>`. Nothing is fetched
 * until a tab (and within it, a relation) is actually opened — a connection can
 * have high fan-out and must never be pulled in eagerly.
 */
import { useState } from 'react';
import { ChevronRight, ExternalLink, Plus, Search, Table2 } from 'lucide-react';
import { useQueryResult, useSchema } from '@/api/hooks';
import type { PolyFieldValue, PolyRow } from '@/api/types';
import {
    appendConnection,
    appendSubcollection,
    instancePath,
    toRequest,
    type QueryPath,
} from '@/query/path';
import { childCollections, collectionByName, connectionsOf } from '@/schema/model';
import { StorageLens } from '@/lens/storage-lens';
import { ConnectionPicker } from '@/mutate/connection-picker';
import { EditFieldsForm } from '@/mutate/edit-fields-form';
import { InsertInstanceDialog } from '@/mutate/insert-instance-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';

export interface SelectedInstance {
    readonly collection: string;
    readonly id: string;
    /** The fields of that instance as they came back in the result row. */
    readonly fields: Record<string, PolyFieldValue>;
}

export interface InspectorProps {
    readonly instance: SelectedInstance;
    readonly onNavigate: (path: QueryPath) => void;
    readonly onClose: () => void;
}

export function Inspector({ instance, onNavigate, onClose }: InspectorProps) {
    const schemaQuery = useSchema();
    const schema = schemaQuery.data;

    const children = schema ? childCollections(schema, instance.collection) : [];
    const connections = schema ? connectionsOf(schema, instance.collection) : [];
    const collection = schema ? collectionByName(schema, instance.collection) : undefined;

    return (
        <aside className="bg-card flex w-[26rem] shrink-0 flex-col overflow-y-auto border-l">
            <div className="flex items-start justify-between gap-2 border-b p-3">
                <div className="min-w-0">
                    <h2 className="flex items-center gap-1.5 text-sm font-medium">
                        <Table2 className="size-3.5 shrink-0" />
                        <span className="font-mono">{instance.collection}</span>
                    </h2>
                    <p
                        className="text-muted-foreground truncate font-mono text-xs"
                        title={instance.id}
                    >
                        {instance.id}
                    </p>
                </div>
                <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={onClose}>
                    Close
                </Button>
            </div>

            <Tabs defaultValue="fields" className="min-h-0 flex-1">
                <TabsList className="mx-3 mt-3 grid w-auto grid-cols-4">
                    <TabsTrigger value="fields" className="text-xs">
                        Fields
                    </TabsTrigger>
                    <TabsTrigger value="subcollections" className="text-xs">
                        Sub ({children.length})
                    </TabsTrigger>
                    <TabsTrigger value="connections" className="text-xs">
                        Conn ({connections.length})
                    </TabsTrigger>
                    <TabsTrigger value="storage" className="text-xs">
                        Storage
                    </TabsTrigger>
                </TabsList>

                <TabsContent value="fields" className="space-y-3 p-3">
                    <FieldList fields={instance.fields} />
                    {collection && (
                        <EditFieldsForm
                            collection={collection}
                            instanceId={instance.id}
                            fields={instance.fields}
                        />
                    )}
                </TabsContent>

                <TabsContent value="subcollections" className="space-y-2 p-3">
                    {children.length === 0 && (
                        <p className="text-muted-foreground text-xs">
                            {instance.collection} has no subcollections.
                        </p>
                    )}
                    {children.map((child) => (
                        <RelationSection
                            key={child.name}
                            title={child.name}
                            subtitle="subcollection"
                            resultSegment={child.name}
                            buildPath={() =>
                                appendSubcollection(
                                    instancePath(instance.collection, instance.id),
                                    child.name,
                                )
                            }
                            onNavigate={onNavigate}
                            action={
                                <InsertInstanceDialog
                                    collection={child}
                                    parentDocUuid={instance.id}
                                    trigger={
                                        <Button
                                            variant="ghost"
                                            size="icon"
                                            className="size-6"
                                            title={`Add a ${child.name} to this instance`}
                                        >
                                            <Plus className="size-3" />
                                        </Button>
                                    }
                                />
                            }
                        />
                    ))}
                </TabsContent>

                <TabsContent value="connections" className="space-y-2 p-3">
                    {connections.length === 0 && (
                        <p className="text-muted-foreground text-xs">
                            {instance.collection} has no connections.
                        </p>
                    )}
                    {connections.map(({ connection, otherCollection }) => (
                        <RelationSection
                            key={connection.name}
                            title={connection.name}
                            subtitle={`connection → ${otherCollection}`}
                            // A self-referential connection would put the same
                            // collection in the path twice, which no driver can
                            // answer; the relation exists but is not traversable.
                            unavailable={
                                otherCollection === instance.collection
                                    ? `both sides are ${instance.collection}, so this cannot be queried: a collection may appear only once in a path`
                                    : undefined
                            }
                            resultSegment={otherCollection}
                            searchable
                            buildPath={() =>
                                appendConnection(
                                    instancePath(instance.collection, instance.id),
                                    connection.name,
                                    otherCollection,
                                )
                            }
                            onNavigate={onNavigate}
                            action={
                                <ConnectionPicker
                                    connection={connection}
                                    thisCollection={instance.collection}
                                    thisInstanceId={instance.id}
                                    otherCollection={otherCollection}
                                />
                            }
                        />
                    ))}
                </TabsContent>

                <TabsContent value="storage" className="p-3">
                    <StorageLens collection={instance.collection} instanceId={instance.id} />
                </TabsContent>
            </Tabs>
        </aside>
    );
}

function FieldList({ fields }: { readonly fields: Record<string, PolyFieldValue> }) {
    const entries = Object.entries(fields).toSorted(([a], [b]) =>
        a === '_id' ? -1 : b === '_id' ? 1 : a.localeCompare(b),
    );

    return (
        <dl className="space-y-1.5">
            {entries.map(([field, value]) => (
                <div key={field} className="grid grid-cols-[8rem_1fr] items-start gap-2">
                    <dt className="text-muted-foreground truncate font-mono text-xs" title={field}>
                        {field}
                    </dt>
                    <dd className="min-w-0 font-mono text-xs break-words">
                        {value === null ? (
                            <span className="text-muted-foreground italic">null</span>
                        ) : (
                            String(value)
                        )}
                    </dd>
                </div>
            ))}
        </dl>
    );
}

interface RelationSectionProps {
    readonly title: string;
    readonly subtitle: string;
    /** The result segment whose fields should be listed. */
    readonly resultSegment: string;
    readonly searchable?: boolean;
    readonly buildPath: () => QueryPath;
    readonly onNavigate: (path: QueryPath) => void;
    /** A mutation affordance for this relation (insert / connect). */
    readonly action?: React.ReactNode;
    /** Set when the relation cannot be queried; explains why. */
    readonly unavailable?: string;
}

function RelationSection({
    title,
    subtitle,
    resultSegment,
    searchable = false,
    buildPath,
    onNavigate,
    action,
    unavailable,
}: RelationSectionProps) {
    const [isOpen, setIsOpen] = useState(false);
    const [search, setSearch] = useState('');

    const path = buildPath();
    // enabled only once opened — this is the lazy part.
    const query = useQueryResult(isOpen && !unavailable ? toRequest(path) : null);
    const rows = query.data?.data.type === 'documents' ? query.data.data.data : [];
    const matches = searchable && search !== '' ? rows.filter((row) => rowMatches(row, search)) : rows;

    return (
        <section className="rounded-md border">
            <div className="flex items-center gap-1 p-2">
                <button
                    type="button"
                    disabled={unavailable !== undefined}
                    title={unavailable}
                    onClick={() => setIsOpen((open) => !open)}
                    className={cn(
                        'flex min-w-0 flex-1 items-center gap-1.5 text-left',
                        unavailable !== undefined && 'opacity-50',
                    )}
                >
                    <ChevronRight
                        className={cn('size-3.5 shrink-0 transition-transform', isOpen && 'rotate-90')}
                    />
                    <span className="truncate font-mono text-xs">{title}</span>
                    <span className="text-muted-foreground shrink-0 text-xs">{subtitle}</span>
                    {isOpen && !query.isPending && (
                        <Badge variant="secondary" className="ml-auto shrink-0 text-xs">
                            {rows.length}
                        </Badge>
                    )}
                </button>
                {action}
                <Button
                    variant="ghost"
                    size="icon"
                    className="size-6"
                    disabled={unavailable !== undefined}
                    title="Open as a query"
                    onClick={() => onNavigate(path)}
                >
                    <ExternalLink className="size-3" />
                </Button>
            </div>

            {unavailable !== undefined && (
                <p className="text-muted-foreground border-t p-2 text-xs">{unavailable}</p>
            )}

            {isOpen && unavailable === undefined && (
                <div className="space-y-2 border-t p-2">
                    {query.isPending && <Skeleton className="h-8 w-full" />}
                    {query.isError && (
                        <p className="text-destructive text-xs break-words">
                            {query.error instanceof Error ? query.error.message : 'query failed'}
                        </p>
                    )}
                    {query.data && rows.length === 0 && (
                        <p className="text-muted-foreground text-xs">Nothing here.</p>
                    )}
                    {searchable && rows.length > 0 && (
                        <div className="relative">
                            <Search className="text-muted-foreground absolute top-2 left-2 size-3" />
                            <Input
                                value={search}
                                onChange={(event) => setSearch(event.target.value)}
                                placeholder="Search"
                                className="h-7 pl-7 text-xs"
                            />
                        </div>
                    )}
                    {matches.map((row, index) => (
                        <RelationRow key={index} row={row} segment={resultSegment} />
                    ))}
                    {searchable && search !== '' && matches.length === 0 && rows.length > 0 && (
                        <p className="text-muted-foreground text-xs">No match.</p>
                    )}
                </div>
            )}
        </section>
    );
}

function RelationRow({ row, segment }: { readonly row: PolyRow; readonly segment: string }) {
    // A connection query returns the connection's own data alongside the target
    // collection's fields; show both, target first.
    const target = row[segment] ?? {};
    const extras = Object.entries(row).filter(([key]) => key !== segment);

    return (
        <div className="bg-background space-y-1 rounded border p-2">
            <div className="flex flex-wrap gap-x-3 gap-y-0.5">
                {Object.entries(target)
                    .filter(([field]) => field !== '_id')
                    .map(([field, value]) => (
                        <span key={field} className="font-mono text-xs">
                            <span className="text-muted-foreground">{field}=</span>
                            {formatValue(value)}
                        </span>
                    ))}
            </div>
            {extras.map(([key, fields]) => {
                const entries = Object.entries(fields).filter(([field]) => field !== '_id');
                if (entries.length === 0) return null;
                return (
                    <div key={key} className="flex flex-wrap gap-x-3 gap-y-0.5">
                        <span className="text-muted-foreground text-xs italic">{key}:</span>
                        {entries.map(([field, value]) => (
                            <span key={field} className="font-mono text-xs">
                                <span className="text-muted-foreground">{field}=</span>
                                {formatValue(value)}
                            </span>
                        ))}
                    </div>
                );
            })}
        </div>
    );
}

function formatValue(value: PolyFieldValue): string {
    return value === null ? 'null' : String(value);
}

function rowMatches(row: PolyRow, search: string): boolean {
    const needle = search.toLowerCase();
    return Object.values(row).some((fields) =>
        Object.values(fields).some((value) => String(value).toLowerCase().includes(needle)),
    );
}
