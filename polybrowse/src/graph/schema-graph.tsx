/**
 * One React Flow pane showing the schema in one store view.
 */
import { useEffect, useMemo } from 'react';
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
import { SchemaEdge } from './edges';
import type { GraphNode, Mappings } from './graph-model';
import { useGraphContext } from './highlight-context';
import { layoutGraph, type FlowEdge, type FlowNode } from './layout';
import { ConnectionNode, DocumentNode, EntityNode, LabelNode } from './nodes';
import { buildStoreGraph, type StoreView } from './store-graph';

const nodeTypes = {
    entity: EntityNode,
    label: LabelNode,
    document: DocumentNode,
    connection: ConnectionNode,
} satisfies Record<GraphNode['kind'], unknown>;

const edgeTypes = { schema: SchemaEdge };

/** A request to centre one node; `nonce` lets the same node be focused twice. */
export interface FocusRequest {
    readonly nodeId: string;
    readonly nonce: number;
}

export interface SchemaGraphProps {
    readonly view: StoreView;
    readonly schema: DatabaseSchema;
    /** Required for the physical views (see needsMappings). */
    readonly mappings: Mappings | null;
    readonly focus: FocusRequest | null;
}

export function SchemaGraph({ view, schema, mappings, focus }: SchemaGraphProps) {
    const laidOut = useMemo(
        () => layoutGraph(buildStoreGraph(view, schema, mappings)),
        [view, schema, mappings],
    );
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
    const { setHovered } = useGraphContext();
    const { theme } = useTheme();

    const onEdgeMouseEnter: EdgeMouseHandler<FlowEdge> = (_, edge) => {
        if (edge.data) setHovered(edge.data.edge.keys);
    };

    return (
        <ReactFlow<FlowNode, FlowEdge>
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
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
