/**
 * React Flow node components for the schema graphs. Sizes are fixed (see
 * layout.ts) so that the dagre layout computed up front matches what renders.
 *
 * Every header and row reports hover with its mapping keys, and reads the
 * shared hover state to light up or dim itself.
 */
import type { CSSProperties } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Link2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { assertNever } from '@/lib/assert-never';
import { highlightOf, useGraphContext, type HighlightState } from './highlight-context';
import {
    CONNECTION_HEADER_HEIGHT,
    CONNECTION_WIDTH,
    ENTITY_HEADER_HEIGHT,
    ENTITY_WIDTH,
    ROW_HEIGHT,
    type FlowNode,
} from './layout';
import type { GraphNode, GraphRow, RowTone } from './store-graph';

export function EntityNode({ data }: NodeProps<FlowNode>) {
    const { node } = data;
    const { hovered, setHovered, colors } = useGraphContext();
    const color = (node.colorOf && colors.get(node.colorOf)) ?? 'var(--muted-foreground)';
    const nodeState = containerHighlight(hovered, node);

    return (
        <div
            style={{ width: ENTITY_WIDTH, '--node-color': color } as CSSProperties}
            className={cn(
                'bg-card text-card-foreground overflow-hidden rounded-lg border-2 shadow-sm transition-opacity',
                nodeState === 'dimmed' && 'opacity-35',
            )}
        >
            <Handle type="target" position={Position.Left} className="opacity-0" />
            <div
                style={{ height: ENTITY_HEADER_HEIGHT }}
                className={cn(
                    'flex flex-col justify-center border-b px-3',
                    highlightOf(hovered, node.keys) === 'active'
                        ? 'bg-[color-mix(in_oklch,var(--node-color)_45%,var(--card))]'
                        : 'bg-[color-mix(in_oklch,var(--node-color)_20%,var(--card))]',
                )}
                onMouseEnter={() => setHovered(node.keys)}
                onMouseLeave={() => setHovered(null)}
            >
                <span className="truncate font-mono text-sm font-semibold">{node.title}</span>
                {node.caption && (
                    <span className="text-muted-foreground truncate text-[11px]">
                        {node.caption}
                    </span>
                )}
            </div>
            <div className="py-1">
                {node.rows.map((row) => (
                    <Row key={row.id} row={row} />
                ))}
            </div>
            <Handle type="source" position={Position.Right} className="opacity-0" />
        </div>
    );
}

export function ConnectionNode({ data }: NodeProps<FlowNode>) {
    const { node } = data;
    const { hovered, setHovered } = useGraphContext();
    const nodeState = containerHighlight(hovered, node);

    return (
        <div
            style={{ width: CONNECTION_WIDTH }}
            className={cn(
                'bg-card text-card-foreground overflow-hidden rounded-2xl border-2 border-dashed shadow-sm transition-opacity',
                nodeState === 'dimmed' && 'opacity-35',
                highlightOf(hovered, node.keys) === 'active' && 'border-primary',
            )}
        >
            <Handle type="target" position={Position.Left} className="opacity-0" />
            <div
                style={{ height: CONNECTION_HEADER_HEIGHT }}
                className="flex items-center justify-center gap-1.5 px-3"
                onMouseEnter={() => setHovered(node.keys)}
                onMouseLeave={() => setHovered(null)}
            >
                <Link2 className="text-muted-foreground size-3.5 shrink-0" />
                <span className="truncate font-mono text-sm font-semibold">{node.title}</span>
            </div>
            {node.rows.length > 0 && (
                <div className="border-t py-1">
                    {node.rows.map((row) => (
                        <Row key={row.id} row={row} />
                    ))}
                </div>
            )}
            <Handle type="source" position={Position.Right} className="opacity-0" />
        </div>
    );
}

function Row({ row }: { readonly row: GraphRow }) {
    const { hovered, setHovered } = useGraphContext();
    const state = highlightOf(hovered, row.keys);

    return (
        <div
            style={{ height: ROW_HEIGHT }}
            className={cn(
                'flex items-center gap-2 px-3 font-mono text-xs',
                state === 'active' &&
                    'bg-[color-mix(in_oklch,var(--node-color,var(--primary))_30%,transparent)]',
            )}
            onMouseEnter={() => setHovered(row.keys)}
            onMouseLeave={() => setHovered(null)}
        >
            <span className={cn('truncate', toneClass(row.tone))}>{row.label}</span>
            {row.detail && (
                <span className="text-muted-foreground ml-auto shrink-0">{row.detail}</span>
            )}
        </div>
    );
}

function toneClass(tone: RowTone): string {
    switch (tone) {
        case 'id':
            return 'font-semibold';
        case 'field':
            return '';
        case 'reference':
            return 'italic';
        default:
            return assertNever(tone);
    }
}

/** A node is dimmed only when neither it nor any of its rows matches. */
function containerHighlight(
    hovered: ReadonlySet<string> | null,
    node: GraphNode,
): HighlightState {
    const own = highlightOf(hovered, node.keys);
    if (own !== 'dimmed') return own;
    return node.rows.some((row) => highlightOf(hovered, row.keys) === 'active')
        ? 'active'
        : 'dimmed';
}
