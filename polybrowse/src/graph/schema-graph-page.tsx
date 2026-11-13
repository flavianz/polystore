/**
 * The Schema view: the whole schema as a graph, in the poly model and in each
 * store's physical layout — one at a time, or the poly model side by side with
 * one store ("Compare"). Hover highlights an element in every pane; a click
 * pins it and explains how it is stored.
 */
import { useMemo, useState } from 'react';
import { AlertCircle, Columns2 } from 'lucide-react';
import { useMappings, type MappingsResult } from '@/api/hooks';
import type { DatabaseSchema } from '@/api/types';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { collectionColors } from './colors';
import { explain } from './explain';
import { ExplanationCard } from './explanation-card';
import { GraphContext, type GraphContextValue } from './highlight-context';
import { keyId, type MappingKey } from './mapping-key';
import { SchemaGraph, type FocusRequest } from './schema-graph';
import { needsMappings, STORE_VIEWS, storeViewLabel, type StoreView } from './store-graph';

const PHYSICAL_VIEWS = STORE_VIEWS.filter(needsMappings);

export interface SchemaGraphPageProps {
    readonly schema: DatabaseSchema;
    readonly focus: FocusRequest | null;
    /** Leaves the schema view and browses this collection. */
    readonly onBrowse: (collection: string) => void;
}

export function SchemaGraphPage({ schema, focus, onBrowse }: SchemaGraphPageProps) {
    const [view, setView] = useState<StoreView>('poly');
    const [compare, setCompare] = useState(false);
    const [hovered, setHoveredState] = useState<ReadonlySet<string> | null>(null);
    const [selected, setSelected] = useState<MappingKey | null>(null);

    const collectionNames = useMemo(
        () => schema.collections.map((collection) => collection.name),
        [schema],
    );
    const mappings = useMappings(collectionNames);
    const colors = useMemo(() => collectionColors(schema), [schema]);

    // Hover wins; otherwise the pinned selection stays lit.
    const highlighted = hovered ?? (selected ? new Set([keyId(selected)]) : null);
    const context: GraphContextValue = {
        highlighted,
        setHovered: (keys) => setHoveredState(keys === null ? null : new Set(keys.map(keyId))),
        select: setSelected,
        colors,
    };

    const explanation = selected && mappings.data ? explain(selected, schema, mappings.data) : null;

    // In Compare the left pane is always the poly model; the tabs pick the store.
    const rightView: StoreView = view === 'poly' ? 'postgres' : view;
    const tabViews: readonly StoreView[] = compare ? PHYSICAL_VIEWS : STORE_VIEWS;

    const pane = (paneView: StoreView) => (
        <GraphPane
            view={paneView}
            schema={schema}
            mappings={mappings}
            focus={focus}
            showTitle={compare}
        />
    );

    return (
        <GraphContext value={context}>
            <div className="flex min-h-0 flex-1 flex-col">
                <div className="flex flex-wrap items-center gap-3 border-b px-4 py-2">
                    <Tabs
                        value={compare ? rightView : view}
                        onValueChange={(value) => setView(value as StoreView)}
                    >
                        <TabsList>
                            {tabViews.map((option) => (
                                <TabsTrigger key={option} value={option}>
                                    {storeViewLabel(option)}
                                </TabsTrigger>
                            ))}
                        </TabsList>
                    </Tabs>
                    <Button
                        variant={compare ? 'secondary' : 'outline'}
                        size="sm"
                        aria-pressed={compare}
                        onClick={() => {
                            setCompare((on) => !on);
                            if (!compare && view === 'poly') setView('postgres');
                        }}
                    >
                        <Columns2 />
                        Compare with Polystore
                    </Button>
                    <span className="text-muted-foreground text-xs">
                        Hover to highlight across views · click to explain · drag to rearrange
                    </span>
                </div>

                <div className="relative flex min-h-0 flex-1">
                    {schema.collections.length === 0 ? (
                        <p className="text-muted-foreground p-4 text-sm">
                            The polystore has no collections yet.
                        </p>
                    ) : compare ? (
                        <>
                            <div className="min-w-0 flex-1 border-r">{pane('poly')}</div>
                            <div className="min-w-0 flex-1">{pane(rightView)}</div>
                        </>
                    ) : (
                        <div className="min-w-0 flex-1">{pane(view)}</div>
                    )}

                    {explanation && (
                        <ExplanationCard
                            explanation={explanation}
                            onClose={() => setSelected(null)}
                            onBrowse={onBrowse}
                        />
                    )}
                </div>
            </div>
        </GraphContext>
    );
}

interface GraphPaneProps {
    readonly view: StoreView;
    readonly schema: DatabaseSchema;
    readonly mappings: MappingsResult;
    readonly focus: FocusRequest | null;
    readonly showTitle: boolean;
}

/** One pane, with the loading/error states of the mappings it may need. */
function GraphPane({ view, schema, mappings, focus, showTitle }: GraphPaneProps) {
    const body = () => {
        if (!needsMappings(view)) {
            return <SchemaGraph view={view} schema={schema} mappings={null} focus={focus} />;
        }
        if (mappings.error) {
            return (
                <div className="border-destructive/50 bg-destructive/10 m-4 flex items-start gap-2 rounded-md border p-3 text-sm">
                    <AlertCircle className="text-destructive mt-0.5 size-4 shrink-0" />
                    <div>
                        <p className="font-medium">Could not load the storage mapping</p>
                        <p className="text-muted-foreground">{mappings.error.message}</p>
                    </div>
                </div>
            );
        }
        if (!mappings.data) {
            return (
                <div className="p-4">
                    <Skeleton className="h-64 w-full" />
                </div>
            );
        }
        return <SchemaGraph view={view} schema={schema} mappings={mappings.data} focus={focus} />;
    };

    return (
        <div className="relative h-full">
            {showTitle && (
                <span className="bg-card text-muted-foreground absolute top-3 left-3 z-10 rounded-md border px-2 py-1 text-xs font-medium">
                    {storeViewLabel(view)}
                </span>
            )}
            {body()}
        </div>
    );
}
