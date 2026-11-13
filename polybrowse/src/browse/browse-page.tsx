/**
 * The browse view: schema sidebar + path bar + result table.
 * Everything here is a rendering of exactly one query path.
 */
import { useMemo, useState } from 'react';
import { AlertCircle, Clock, Cpu } from 'lucide-react';
import { useQueryResult, useSchema } from '@/api/hooks';
import { assertNever } from '@/lib/assert-never';
import {
    appendConnection,
    appendSubcollection,
    currentCollection,
    rootPath,
    toRequest,
    truncate,
    type PathStep,
    type QueryPath,
} from '@/query/path';
import { useQueryPath } from '@/query/use-query-path';
import { SchemaSidebar } from '@/schema/schema-sidebar';
import { Badge } from '@/components/ui/badge';
import { SidebarInset, SidebarTrigger } from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { PathBar } from './path-bar';
import { ResultTable } from './result-table';

export function BrowsePage() {
    const { path, setPath } = useQueryPath();
    const [selectedStep, setSelectedStep] = useState<number | null>(null);
    const [selectedRow, setSelectedRow] = useState<number | null>(null);

    const schemaQuery = useSchema();
    const request = useMemo(() => (path ? toRequest(path) : null), [path]);
    const resultQuery = useQueryResult(request);

    const navigate = (next: QueryPath | null) => {
        setPath(next);
        setSelectedStep(null);
        setSelectedRow(null);
    };

    return (
        <>
            <SchemaSidebar
                schema={schemaQuery.data}
                isLoading={schemaQuery.isLoading}
                currentCollection={path ? currentCollection(path) : null}
                onSelectCollection={(name) => navigate(rootPath(name))}
                onAppendSubcollection={(name) =>
                    path && navigate(appendSubcollection(path, name))
                }
                onAppendConnection={(connectionName, target) =>
                    path && navigate(appendConnection(path, connectionName, target))
                }
            />

            <SidebarInset>
                <header className="flex min-h-14 items-center gap-2 border-b px-4 py-2">
                    <SidebarTrigger />
                    {path ? (
                        <PathBar
                            path={path}
                            selectedStep={selectedStep}
                            onSelectStep={(index) =>
                                setSelectedStep((current) => (current === index ? null : index))
                            }
                            onTruncate={(index) => navigate(truncate(path, index))}
                        />
                    ) : (
                        <span className="text-muted-foreground text-sm">
                            Pick a collection in the sidebar to start a query.
                        </span>
                    )}
                </header>

                <main className="min-w-0 flex-1 space-y-4 p-4">
                    {schemaQuery.isError && (
                        <ErrorNote
                            title="Could not load the schema"
                            detail={describeError(schemaQuery.error)}
                        />
                    )}

                    {!path && !schemaQuery.isError && (
                        <p className="text-muted-foreground text-sm">
                            Every view in polybrowse is one query path. Choosing a collection
                            starts one; subcollections and connections extend it.
                        </p>
                    )}

                    {path && resultQuery.isPending && (
                        <div className="space-y-2">
                            <Skeleton className="h-9 w-full" />
                            <Skeleton className="h-9 w-full" />
                            <Skeleton className="h-9 w-4/5" />
                        </div>
                    )}

                    {path && resultQuery.isError && (
                        <ErrorNote
                            title="The query failed"
                            detail={describeError(resultQuery.error)}
                        />
                    )}

                    {resultQuery.data && (
                        <>
                            <ExecutionStrip
                                driver={resultQuery.data.env.driver}
                                queryBuilding={resultQuery.data.duration.queryBuilding}
                                queryExecuting={resultQuery.data.duration.queryExecuting}
                                rowCount={
                                    resultQuery.data.data.type === 'documents'
                                        ? resultQuery.data.data.data.length
                                        : null
                                }
                            />
                            {renderResult(resultQuery.data.data, path, selectedRow, setSelectedRow)}
                        </>
                    )}
                </main>
            </SidebarInset>
        </>
    );
}

function renderResult(
    data: NonNullable<ReturnType<typeof useQueryResult>['data']>['data'],
    path: QueryPath | null,
    selectedRow: number | null,
    onSelectRow: (index: number) => void,
) {
    switch (data.type) {
        case 'documents':
            return (
                <ResultTable
                    rows={data.data}
                    segmentOrder={path ? segmentOrder(path) : []}
                    selectedRow={selectedRow}
                    onSelectRow={onSelectRow}
                />
            );
        case 'count':
            return <p className="text-sm">Count: {data.count}</p>;
        default:
            return assertNever(data);
    }
}

/** Segment names in the order the server will key the result rows by. */
function segmentOrder(path: QueryPath): readonly string[] {
    return path.flatMap((step: PathStep) => {
        switch (step.kind) {
            case 'collection':
                return [step.name];
            case 'connection':
                return [step.connectionName, step.collectionName];
            default:
                return assertNever(step);
        }
    });
}

interface ExecutionStripProps {
    readonly driver: string;
    readonly queryBuilding: string;
    readonly queryExecuting: string;
    readonly rowCount: number | null;
}

function ExecutionStrip({
    driver,
    queryBuilding,
    queryExecuting,
    rowCount,
}: ExecutionStripProps) {
    return (
        <div className="text-muted-foreground flex flex-wrap items-center gap-3 text-xs">
            <span className="flex items-center gap-1.5">
                <Cpu className="size-3.5" />
                routed to
                <Badge variant="outline" className="font-mono">
                    {driver}
                </Badge>
            </span>
            <span className="flex items-center gap-1.5">
                <Clock className="size-3.5" />
                build {queryBuilding} · exec {queryExecuting}
            </span>
            {rowCount !== null && <span>{rowCount} rows</span>}
        </div>
    );
}

function ErrorNote({ title, detail }: { readonly title: string; readonly detail: string }) {
    return (
        <div className="border-destructive/50 bg-destructive/10 flex items-start gap-2 rounded-md border p-3 text-sm">
            <AlertCircle className="text-destructive mt-0.5 size-4 shrink-0" />
            <div className="min-w-0">
                <p className="font-medium">{title}</p>
                <p className="text-muted-foreground break-words">{detail}</p>
            </div>
        </div>
    );
}

function describeError(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}
