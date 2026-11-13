/**
 * One React Flow pane showing the schema in one store view.
 */
import { useEffect, useMemo, type CSSProperties } from 'react';
import {
    Background,
    Controls,
    ReactFlow,
    useNodesState,
    useReactFlow,
    type EdgeMouseHandler,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { DatabaseSchema } from '@/api/types';
import { useTheme } from '@/components/theme-context';
import { assertNever } from '@/lib/assert-never';
import { highlightOf, useGraphContext } from './highlight-context';
import { layoutGraph, type FlowEdge, type FlowNode } from './layout';
import { ConnectionNode, EntityNode } from './nodes';
import { buildStoreGraph, type GraphEdge, type GraphNode, type StoreView } from './store-graph';

const nodeTypes = {
    entity: EntityNode,
    connection: ConnectionNode,
} satisfies Record<GraphNode['kind'], unknown>;

/** A request to centre one node; `nonce` lets the same node be focused twice. */
export interface FocusRequest {
    readonly nodeId: string;
    readonly nonce: number;
}

export interface SchemaGraphProps {
    readonly view: StoreView;
    readonly schema: DatabaseSchema;
    readonly focus: FocusRequest | null;
}

export function SchemaGraph({ view, schema, focus }: SchemaGraphProps) {
    const laidOut = useMemo(() => layoutGraph(buildStoreGraph(view, schema)), [view, schema]);
    // Remount when the layout changes, so dragged positions reset to the fresh
    // layout instead of being reconciled against it.
    const layoutKey = useMemo(
        () => `${view}:${laidOut.nodes.map((node) => node.id).join('|')}`,
        [view, laidOut],
    );

    return (
        <FlowPane
            key={layoutKey}
            initialNodes={laidOut.nodes}
            edges={laidOut.edges}
            focus={focus}
        />
    );
}

interface FlowPaneProps {
    readonly initialNodes: FlowNode[];
    readonly edges: FlowEdge[];
    readonly focus: FocusRequest | null;
}

function FlowPane({ initialNodes, edges, focus }: FlowPaneProps) {
    const [nodes, , onNodesChange] = useNodesState(initialNodes);
    const { hovered, setHovered } = useGraphContext();
    const { theme } = useTheme();

    const styledEdges = edges.map((edge) => styleEdge(edge, hovered));

    const onEdgeMouseEnter: EdgeMouseHandler<FlowEdge> = (_, edge) => {
        if (edge.data) setHovered(edge.data.edge.keys);
    };

    return (
        <ReactFlow<FlowNode, FlowEdge>
            nodes={nodes}
            edges={styledEdges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgeMouseEnter={onEdgeMouseEnter}
            onEdgeMouseLeave={() => setHovered(null)}
            colorMode={theme}
            nodesConnectable={false}
            edgesFocusable={false}
            fitView
            minZoom={0.2}
            proOptions={{ hideAttribution: true }}
        >
            <Background gap={24} size={1} />
            <Controls showInteractive={false} />
            <FocusController focus={focus} />
        </ReactFlow>
    );
}

function FocusController({ focus }: { readonly focus: FocusRequest | null }) {
    const { fitView } = useReactFlow();
    useEffect(() => {
        if (!focus) return;
        void fitView({ nodes: [{ id: focus.nodeId }], duration: 500, maxZoom: 1.2 });
    }, [focus, fitView]);
    return null;
}

function styleEdge(edge: FlowEdge, hovered: ReadonlySet<string> | null): FlowEdge {
    const model = edge.data?.edge;
    if (!model) return edge;
    const state = highlightOf(hovered, model.keys);
    const base = baseEdgeStyle(model);
    return {
        ...edge,
        animated: state === 'active',
        style: {
            ...base,
            ...(state === 'active' && { stroke: 'var(--primary)', strokeWidth: 3 }),
            ...(state === 'dimmed' && { opacity: 0.2 }),
        },
        labelStyle: { fontFamily: 'var(--font-mono, monospace)', fontSize: 11 },
    };
}

function baseEdgeStyle(edge: GraphEdge): CSSProperties {
    switch (edge.kind) {
        case 'contains':
            return { strokeWidth: 2 };
        case 'connection':
            return { strokeWidth: 1.5, strokeDasharray: '6 4' };
        default:
            return assertNever(edge.kind);
    }
}
