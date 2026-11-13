/**
 * The app shell: schema sidebar plus either the browse view (path bar + result
 * table — everything a rendering of exactly one query path) or the schema
 * graph view.
 */
import { useMemo, useState } from 'react';
import { AlertCircle, Clock, Cpu, Network, Table2 } from 'lucide-react';
import { useQueryResult, useSchema } from '@/api/hooks';
import { assertNever } from '@/lib/assert-never';
import {
    appendConnection,
    appendSubcollection,
    currentCollection,
    pathNames,
    replaceStep,
    rootPath,
    truncate,
    tryToRequest,
    type PathStep,
    type QueryPath,
} from '@/query/path';
import { useQueryPath } from '@/query/use-query-path';
import type { AppView } from '@/query/url';
import { SchemaGraphPage } from '@/graph/schema-graph-page';
import type { FocusRequest } from '@/graph/schema-graph';
import { collectionNodeId } from '@/graph/graph-model';
import { SchemaSidebar } from '@/schema/schema-sidebar';
import { collectionByName } from '@/schema/model';
import { NativeQueries } from '@/lens/native-queries';
import { InsertInstanceDialog } from '@/mutate/insert-instance-dialog';
import type { DriverType, PolyRow } from '@/api/types';
import { Badge } from '@/components/ui/badge';
import { SidebarInset, SidebarTrigger } from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { FilterEditor } from './filter-editor';
import { Inspector, type SelectedInstance } from './inspector';
import { PathBar } from './path-bar';
import { RawQueryPanel } from './raw-query-panel';
import { ResultTable } from './result-table';

export function BrowsePage() {
    const { path, view, setPath, replacePath, setView, setLocation } = useQueryPath();
    const [graphFocus, setGraphFocus] = useState<FocusRequest | null>(null);
    const [selectedStep, setSelectedStep] = useState<number | null>(null);
    const [selectedRow, setSelectedRow] = useState<number | null>(null);
    const [forceDriver, setForceDriver] = useState<DriverType | null>(null);

    const schemaQuery = useSchema();

    // A filter being edited can be temporarily unserializable (an empty UUID,
    // say), so the request is built defensively — see tryToRequest.
    const requestResult = useMemo(() => (path ? tryToRequest(path) : null), [path]);
    const request = requestResult && 'request' in requestResult ? requestResult.request : null;
    const requestError =
        requestResult && 'error' in requestResult ? requestResult.error : null;
    const resultQuery = useQueryResult(request, forceDriver);

    const rows =
        resultQuery.data?.data.type === 'documents' ? resultQuery.data.data.data : undefined;
    const selectedInstance =
        path && rows && selectedRow !== null
            ? resolveInstance(path, rows[selectedRow])
            : null;

    const currentName = path ? currentCollection(path) : null;
    const currentModel =
        currentName && schemaQuery.data
            ? collectionByName(schemaQuery.data, currentName)
            : undefined;
    const currentRootCollection =
        currentModel && currentModel.parentCollection === null ? currentModel : null;

    const navigate = (next: QueryPath | null) => {
        setPath(next);
        setSelectedStep(null);
        setSelectedRow(null);
    };

    /**
     * Editing a step's filters is not navigation: it replaces the current
     * history entry so that Back does not walk through every keystroke, and it
     * keeps the editor open.
     */
    const editStep = (index: number, step: PathStep) => {
        if (!path) return;
        replacePath(replaceStep(path, index, step));
        setSelectedRow(null);
    };

    /** In the schema view the sidebar does not build paths; it centres nodes. */
    const focusCollection = (name: string) =>
        setGraphFocus((current) => ({
            nodeId: collectionNodeId(name),
            nonce: (current?.nonce ?? 0) + 1,
        }));

    if (view === 'schema') {
        return (
            <>
                <SchemaSidebar
                    schema={schemaQuery.data}
                    isLoading={schemaQuery.isLoading}
                    path={null}
                    onSelectCollection={focusCollection}
                    onAppendSubcollection={focusCollection}
                    onAppendConnection={(_, target) => focusCollection(target)}
                />
                <SidebarInset className="min-w-0 overflow-hidden">
                    <header className="flex min-h-14 items-center gap-2 border-b px-4 py-2">
                        <SidebarTrigger />
                        <ViewSwitch view={view} onChange={setView} />
                    </header>
                    {schemaQuery.isError && (
                        <div className="p-4">
                            <ErrorNote
                                title="Could not load the schema"
                                detail={describeError(schemaQuery.error)}
                            />
                        </div>
                    )}
                    {schemaQuery.isLoading && (
                        <div className="p-4">
                            <Skeleton className="h-64 w-full" />
                        </div>
                    )}
                    {schemaQuery.data && (
                        <SchemaGraphPage
                            schema={schemaQuery.data}
                            focus={graphFocus}
                            onBrowse={(name) => {
                                setLocation(rootPath(name), 'browse');
                                setSelectedStep(null);
                                setSelectedRow(null);
                            }}
                        />
                    )}
                </SidebarInset>
            </>
        );
    }

    return (
        <>
            <SchemaSidebar
                schema={schemaQuery.data}
                isLoading={schemaQuery.isLoading}
                path={path}
                onSelectCollection={(name) => navigate(rootPath(name))}
                onAppendSubcollection={(name) =>
                    path && navigate(appendSubcollection(path, name))
                }
                onAppendConnection={(connectionName, target) =>
                    path && navigate(appendConnection(path, connectionName, target))
                }
            />

            <SidebarInset className="min-w-0 flex-row overflow-hidden">
                <div className="flex min-w-0 flex-1 flex-col overflow-y-auto">
                <header className="flex min-h-14 items-center gap-2 border-b px-4 py-2">
                    <SidebarTrigger />
                    <ViewSwitch view={view} onChange={setView} />
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

                    {path && selectedStep !== null && schemaQuery.data && path[selectedStep] && (
                        <FilterEditor
                            schema={schemaQuery.data}
                            step={path[selectedStep]}
                            onChange={(step) => editStep(selectedStep, step)}
                            onClose={() => setSelectedStep(null)}
                        />
                    )}

                    {requestError && (
                        <div className="border-muted bg-muted/40 flex items-start gap-2 rounded-md border p-3 text-sm">
                            <AlertCircle className="mt-0.5 size-4 shrink-0" />
                            <div className="min-w-0">
                                <p className="font-medium">Filter is incomplete</p>
                                <p className="text-muted-foreground break-words">
                                    {requestError}. The query is not run until it can be
                                    expressed in the condition DSL.
                                </p>
                            </div>
                        </div>
                    )}

                    {path && !requestError && resultQuery.isPending && (
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

                    {/* Inserting into a subcollection needs a parent instance, so
                        the top-level affordance only appears for root collections;
                        subcollections are inserted from the inspector. */}
                    {currentRootCollection && (
                        <InsertInstanceDialog
                            collection={currentRootCollection}
                            parentDocUuid={null}
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

                    {path && <RawQueryPanel path={path} onApply={navigate} />}

                    {path && (
                        <NativeQueries
                            path={path}
                            forceDriver={forceDriver}
                            onForceDriverChange={setForceDriver}
                            executedBy={resultQuery.data?.env.driver ?? null}
                        />
                    )}
                </main>
                </div>

                {selectedInstance && (
                    <Inspector
                        instance={selectedInstance}
                        onNavigate={navigate}
                        onClose={() => setSelectedRow(null)}
                    />
                )}
            </SidebarInset>
        </>
    );
}

/**
 * The inspected instance is the one the path points at — the last segment's
 * collection. Returns null when `_id` was projected away by an `only`, since
 * nothing can be addressed without it.
 */
function resolveInstance(path: QueryPath, row: PolyRow | undefined): SelectedInstance | null {
    if (!row) return null;
    const collection = currentCollection(path);
    if (!collection) return null;

    const fields = row[collection];
    const id = fields?.['_id'];
    if (!fields || typeof id !== 'string') return null;

    return { collection, id, fields };
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
                    segmentOrder={path ? pathNames(path) : []}
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

function ViewSwitch({
    view,
    onChange,
}: {
    readonly view: AppView;
    readonly onChange: (view: AppView) => void;
}) {
    return (
        <Tabs value={view} onValueChange={(value) => onChange(value as AppView)}>
            <TabsList>
                <TabsTrigger value="browse">
                    <Table2 className="size-3.5" />
                    Browse
                </TabsTrigger>
                <TabsTrigger value="schema">
                    <Network className="size-3.5" />
                    Schema
                </TabsTrigger>
            </TabsList>
        </Tabs>
    );
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
