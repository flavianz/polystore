/**
 * The one edge component of the schema graphs: a smooth-step path, styled by
 * edge kind and hover state, with an optional label box that can list the
 * edge's own properties (Neo4j relationship properties), each hoverable.
 */
import type { CSSProperties } from 'react';
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, type EdgeProps } from '@xyflow/react';
import { cn } from '@/lib/utils';
import { assertNever } from '@/lib/assert-never';
import type { GraphEdge, GraphRow } from './graph-model';
import { highlightOf, useGraphContext } from './highlight-context';
import {
    EDGE_LABEL_HEADER_HEIGHT,
    EDGE_LABEL_ROW_HEIGHT,
    EDGE_LABEL_WIDTH,
    type FlowEdge,
} from './layout';

export function SchemaEdge({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    markerStart,
    markerEnd,
    data,
}: EdgeProps<FlowEdge>) {
    const { hovered, setHovered } = useGraphContext();
    if (!data) return null;
    const { edge } = data;
    const state = highlightOf(hovered, edge.keys);
    const rowActive = edge.rows.some((row) => highlightOf(hovered, row.keys) === 'active');
    const active = state === 'active' || rowActive;

    const [path, labelX, labelY] = getSmoothStepPath({
        sourceX,
        sourceY,
        targetX,
        targetY,
        sourcePosition,
        targetPosition,
        borderRadius: 10,
    });

    const style: CSSProperties = {
        ...kindStyle(edge),
        ...(active && { stroke: 'var(--primary)', strokeWidth: 3 }),
        ...(state === 'dimmed' && !rowActive && { opacity: 0.2 }),
        transition: 'opacity 150ms, stroke 150ms',
    };

    const hasLabel = edge.label !== null || edge.rows.length > 0;

    return (
        <>
            <BaseEdge
                path={path}
                markerStart={markerStart}
                markerEnd={markerEnd}
                style={style}
                interactionWidth={16}
            />
            {hasLabel && (
                <EdgeLabelRenderer>
                    <div
                        style={{
                            width: EDGE_LABEL_WIDTH,
                            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
                        }}
                        className={cn(
                            'nodrag nopan bg-card text-card-foreground pointer-events-auto absolute overflow-hidden rounded-md border font-mono text-[11px] shadow-sm transition-opacity',
                            active && 'border-primary',
                            state === 'dimmed' && !rowActive && 'opacity-30',
                        )}
                    >
                        {edge.label !== null && (
                            <div
                                style={{ height: EDGE_LABEL_HEADER_HEIGHT }}
                                className={cn(
                                    'flex items-center justify-center px-2 font-semibold',
                                    state === 'active' && 'bg-primary/20',
                                )}
                                onMouseEnter={() => setHovered(edge.keys)}
                                onMouseLeave={() => setHovered(null)}
                            >
                                <span className="truncate">{edge.label}</span>
                            </div>
                        )}
                        {edge.rows.length > 0 && (
                            <div className="border-t py-0.5">
                                {edge.rows.map((row) => (
                                    <EdgeRow key={row.id} row={row} />
                                ))}
                            </div>
                        )}
                    </div>
                </EdgeLabelRenderer>
            )}
        </>
    );
}

function EdgeRow({ row }: { readonly row: GraphRow }) {
    const { hovered, setHovered } = useGraphContext();
    return (
        <div
            style={{ height: EDGE_LABEL_ROW_HEIGHT }}
            title={row.hint}
            className={cn(
                'flex items-center gap-2 px-2',
                highlightOf(hovered, row.keys) === 'active' && 'bg-primary/25',
            )}
            onMouseEnter={() => setHovered(row.keys)}
            onMouseLeave={() => setHovered(null)}
        >
            <span className="truncate">{row.label}</span>
            {row.detail && (
                <span className="text-muted-foreground ml-auto shrink-0">{row.detail}</span>
            )}
        </div>
    );
}

function kindStyle(edge: GraphEdge): CSSProperties {
    switch (edge.kind) {
        case 'contains':
            return { strokeWidth: 2 };
        case 'connection':
            return { strokeWidth: 1.5, strokeDasharray: '6 4' };
        case 'foreignKey':
            return { strokeWidth: 1.5 };
        case 'relationship':
            return { strokeWidth: 2 };
        default:
            return assertNever(edge.kind);
    }
}
