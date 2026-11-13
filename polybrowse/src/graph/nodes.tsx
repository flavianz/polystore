/**
 * React Flow node components for the schema graphs. Sizes are fixed (see
 * layout.ts) so that the dagre layout computed up front matches what renders.
 *
 * Every header and row reports hover with its mapping keys, and reads the
 * shared hover state to light up or dim itself. Every row has its own pair of
 * invisible handles so an edge can attach to a column (a foreign key).
 */
import type { CSSProperties, ReactNode } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Braces, Link2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { assertNever } from '@/lib/assert-never';
import type { GraphNode, GraphRow, RowTone } from './graph-model';
import { highlightOf, useGraphContext, type HighlightState } from './highlight-context';
import {
    CONNECTION_HEADER_HEIGHT,
    CONNECTION_WIDTH,
    ENTITY_HEADER_HEIGHT,
    ENTITY_WIDTH,
    NODE_HANDLE_IN,
    NODE_HANDLE_OUT,
    ROW_HEIGHT,
    rowHandleIn,
    rowHandleOut,
    type FlowNode,
} from './layout';

/** A collection or a table: square card with a tinted header. */
export function EntityNode({ data }: NodeProps<FlowNode>) {
    return <CardNode node={data.node} shape="card" />;
}

/** A Neo4j label: rounded card whose header is a filled pill, like Neo4j Browser. */
export function LabelNode({ data }: NodeProps<FlowNode>) {
    return <CardNode node={data.node} shape="label" />;
}

/** A MongoDB collection: a card showing the nested shape of its documents. */
export function DocumentNode({ data }: NodeProps<FlowNode>) {
    return <CardNode node={data.node} shape="document" />;
}

type CardShape = 'card' | 'label' | 'document';

function CardNode({ node, shape }: { readonly node: GraphNode; readonly shape: CardShape }) {
    const { highlighted, setHovered, select, colors } = useGraphContext();
    const color = (node.colorOf && colors.get(node.colorOf)) ?? 'var(--muted-foreground)';
    const headerActive = highlightOf(highlighted, node.keys) === 'active';

    return (
        <NodeFrame
            node={node}
            width={ENTITY_WIDTH}
            className={cn(
                'border-2',
                shapeClass(shape),
                headerActive && 'border-[var(--node-color)]',
            )}
            color={color}
        >
            <div
                style={{ height: ENTITY_HEADER_HEIGHT }}
                className={cn(
                    'flex cursor-pointer flex-col justify-center border-b px-3',
                    shape === 'label' && 'items-center',
                    headerActive
                        ? 'bg-[color-mix(in_oklch,var(--node-color)_45%,var(--card))]'
                        : 'bg-[color-mix(in_oklch,var(--node-color)_20%,var(--card))]',
                )}
                onMouseEnter={() => setHovered(node.keys)}
                onClick={() => select(node.keys[0] ?? null)}
                onMouseLeave={() => setHovered(null)}
            >
                {shape === 'label' ? (
                    <span className="max-w-full truncate rounded-full bg-[var(--node-color)] px-2.5 py-0.5 font-mono text-xs font-semibold text-black">
                        {node.title}
                    </span>
                ) : (
                    <span className="flex items-center gap-1.5 truncate font-mono text-sm font-semibold">
                        {shape === 'document' && (
                            <Braces className="text-muted-foreground size-3.5 shrink-0" />
                        )}
                        {node.title}
                    </span>
                )}
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
        </NodeFrame>
    );
}

/** A poly connection: a dashed chip listing the connection's data fields. */
export function ConnectionNode({ data }: NodeProps<FlowNode>) {
    const { node } = data;
    const { highlighted, setHovered, select } = useGraphContext();

    return (
        <NodeFrame
            node={node}
            width={CONNECTION_WIDTH}
            className={cn(
                'rounded-2xl border-2 border-dashed',
                highlightOf(highlighted, node.keys) === 'active' && 'border-primary',
            )}
            color="var(--primary)"
        >
            <div
                style={{ height: CONNECTION_HEADER_HEIGHT }}
                className="flex cursor-pointer items-center justify-center gap-1.5 px-3"
                onMouseEnter={() => setHovered(node.keys)}
                onClick={() => select(node.keys[0] ?? null)}
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
        </NodeFrame>
    );
}

interface NodeFrameProps {
    readonly node: GraphNode;
    readonly width: number;
    readonly color: string;
    readonly className: string;
    readonly children: ReactNode;
}

/** Shared outer box: size, colour variable, dimming and the node-level handles. */
function NodeFrame({ node, width, color, className, children }: NodeFrameProps) {
    const { highlighted } = useGraphContext();
    return (
        <div
            style={{ width, '--node-color': color } as CSSProperties}
            className={cn(
                'bg-card text-card-foreground relative overflow-hidden shadow-sm transition-opacity',
                containerHighlight(highlighted, node) === 'dimmed' && 'opacity-35',
                className,
            )}
        >
            <Handle
                id={NODE_HANDLE_IN}
                type="target"
                position={Position.Left}
                className="opacity-0"
                isConnectable={false}
            />
            {children}
            <Handle
                id={NODE_HANDLE_OUT}
                type="source"
                position={Position.Right}
                className="opacity-0"
                isConnectable={false}
            />
        </div>
    );
}

function Row({ row }: { readonly row: GraphRow }) {
    const { highlighted, setHovered, select } = useGraphContext();
    const state = highlightOf(highlighted, row.keys);

    return (
        <div
            style={{
                height: ROW_HEIGHT,
                paddingLeft: 12 + (row.depth ?? 0) * 16,
            }}
            title={row.hint}
            className={cn(
                'relative flex cursor-pointer items-center gap-2 pr-3 font-mono text-xs',
                state === 'active' && 'bg-[color-mix(in_oklch,var(--node-color)_30%,transparent)]',
            )}
            onMouseEnter={() => setHovered(row.keys.length > 0 ? row.keys : null)}
            onClick={() => select(row.keys[0] ?? null)}
            onMouseLeave={() => setHovered(null)}
        >
            <Handle
                id={rowHandleIn(row.id)}
                type="target"
                position={Position.Left}
                className="opacity-0"
                isConnectable={false}
            />
            <span className={cn('truncate', toneClass(row.tone))}>{row.label}</span>
            {row.detail && (
                <span className="text-muted-foreground ml-auto shrink-0">{row.detail}</span>
            )}
            <Handle
                id={rowHandleOut(row.id)}
                type="source"
                position={Position.Right}
                className="opacity-0"
                isConnectable={false}
            />
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
        case 'meta':
            return 'text-muted-foreground';
        case 'container':
            return 'font-semibold text-muted-foreground';
        default:
            return assertNever(tone);
    }
}

function shapeClass(shape: CardShape): string {
    switch (shape) {
        case 'card':
            return 'rounded-lg';
        case 'label':
            return 'rounded-[1.5rem]';
        case 'document':
            return 'rounded-xl';
        default:
            return assertNever(shape);
    }
}

/** A node is dimmed only when neither it nor any of its rows matches. */
function containerHighlight(
    highlighted: ReadonlySet<string> | null,
    node: GraphNode,
): HighlightState {
    const own = highlightOf(highlighted, node.keys);
    if (own !== 'dimmed') return own;
    return node.rows.some((row) => highlightOf(highlighted, row.keys) === 'active')
        ? 'active'
        : 'dimmed';
}
