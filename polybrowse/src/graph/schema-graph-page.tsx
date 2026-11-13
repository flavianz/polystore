/**
 * The Schema view: the whole schema as a graph, in the poly model and (in
 * later increments) in each store's physical layout.
 */
import { useMemo, useState } from 'react';
import type { DatabaseSchema } from '@/api/types';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { collectionColors } from './colors';
import { GraphContext, type GraphContextValue } from './highlight-context';
import { keyId, type MappingKey } from './mapping-key';
import { SchemaGraph, type FocusRequest } from './schema-graph';
import { STORE_VIEWS, storeViewLabel, type StoreView } from './store-graph';

export interface SchemaGraphPageProps {
    readonly schema: DatabaseSchema;
    readonly focus: FocusRequest | null;
}

export function SchemaGraphPage({ schema, focus }: SchemaGraphPageProps) {
    const [view, setView] = useState<StoreView>('poly');
    const [hovered, setHoveredState] = useState<ReadonlySet<string> | null>(null);

    const colors = useMemo(() => collectionColors(schema), [schema]);
    const context: GraphContextValue = {
        hovered,
        setHovered: (keys: readonly MappingKey[] | null) =>
            setHoveredState(keys === null ? null : new Set(keys.map(keyId))),
        colors,
    };

    return (
        <GraphContext value={context}>
            <div className="flex min-h-0 flex-1 flex-col">
                <div className="flex items-center gap-3 border-b px-4 py-2">
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
                <div className="min-h-0 flex-1">
                    {schema.collections.length === 0 ? (
                        <p className="text-muted-foreground p-4 text-sm">
                            The polystore has no collections yet.
                        </p>
                    ) : (
                        <SchemaGraph view={view} schema={schema} focus={focus} />
                    )}
                </div>
            </div>
        </GraphContext>
    );
}
