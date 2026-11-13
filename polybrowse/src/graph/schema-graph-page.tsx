/**
 * The Schema view: the whole schema as a graph, in the poly model and in each
 * store's physical layout.
 */
import { useMemo, useState } from 'react';
import { AlertCircle } from 'lucide-react';
import { useMappings } from '@/api/hooks';
import type { DatabaseSchema } from '@/api/types';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { collectionColors } from './colors';
import { GraphContext, type GraphContextValue } from './highlight-context';
import { keyId, type MappingKey } from './mapping-key';
import { SchemaGraph, type FocusRequest } from './schema-graph';
import { needsMappings, STORE_VIEWS, storeViewLabel, type StoreView } from './store-graph';

export interface SchemaGraphPageProps {
    readonly schema: DatabaseSchema;
    readonly focus: FocusRequest | null;
}

export function SchemaGraphPage({ schema, focus }: SchemaGraphPageProps) {
    const [view, setView] = useState<StoreView>('poly');
    const [hovered, setHoveredState] = useState<ReadonlySet<string> | null>(null);

    const collectionNames = useMemo(
        () => schema.collections.map((collection) => collection.name),
        [schema],
    );
    const mappings = useMappings(collectionNames);

    const colors = useMemo(() => collectionColors(schema), [schema]);
    const context: GraphContextValue = {
        hovered,
        setHovered: (keys: readonly MappingKey[] | null) =>
            setHoveredState(keys === null ? null : new Set(keys.map(keyId))),
        colors,
    };

    const renderGraph = () => {
        if (schema.collections.length === 0) {
            return (
                <p className="text-muted-foreground p-4 text-sm">
                    The polystore has no collections yet.
                </p>
            );
        }
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
        return (
            <SchemaGraph view={view} schema={schema} mappings={mappings.data} focus={focus} />
        );
    };

    return (
        <GraphContext value={context}>
            <div className="flex min-h-0 flex-1 flex-col">
                <div className="flex flex-wrap items-center gap-3 border-b px-4 py-2">
                    <Tabs value={view} onValueChange={(value) => setView(value as StoreView)}>
                        <TabsList>
                            {STORE_VIEWS.map((option) => (
                                <TabsTrigger key={option} value={option}>
                                    {storeViewLabel(option)}
                                </TabsTrigger>
                            ))}
                        </TabsList>
                    </Tabs>
                    <span className="text-muted-foreground text-xs">
                        Hover a field or connection to highlight it. Drag nodes to rearrange.
                    </span>
                </div>
                <div className="min-h-0 flex-1">{renderGraph()}</div>
            </div>
        </GraphContext>
    );
}
