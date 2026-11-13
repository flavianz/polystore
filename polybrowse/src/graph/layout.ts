/**
 * Deterministic layered layout (dagre, left → right) of a StoreGraph, turned
 * into React Flow nodes and edges. The same schema always yields the same
 * picture, which matters when it is shown on a slide.
 *
 * Node and edge-label sizes are computed from row counts and must agree with
 * the fixed dimensions used by the components (nodes.tsx, edges.tsx).
 */
import { Graph, layout } from '@dagrejs/dagre';
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import { assertNever } from '@/lib/assert-never';
import type { EdgeArrow, GraphEdge, GraphNode, StoreGraph } from './graph-model';

export const ENTITY_WIDTH = 248;
export const ENTITY_HEADER_HEIGHT = 48;
export const ROW_HEIGHT = 24;
export const CONNECTION_WIDTH = 184;
export const CONNECTION_HEADER_HEIGHT = 32;
export const EDGE_LABEL_WIDTH = 168;
export const EDGE_LABEL_HEADER_HEIGHT = 24;
export const EDGE_LABEL_ROW_HEIGHT = 20;

/** Handle ids: one pair per node, and one pair per row for row-level edges. */
export const NODE_HANDLE_IN = 'node:in';
export const NODE_HANDLE_OUT = 'node:out';
export const rowHandleIn = (row: string) => `row:${row}:in`;
export const rowHandleOut = (row: string) => `row:${row}:out`;

// Type aliases (not interfaces) so they satisfy React Flow's Record constraint.
export type GraphNodeData = { readonly node: GraphNode };
export type GraphEdgeData = { readonly edge: GraphEdge };
export type FlowNode = Node<GraphNodeData, GraphNode['kind']>;
export type FlowEdge = Edge<GraphEdgeData, 'schema'>;

export function nodeSize(node: GraphNode): { width: number; height: number } {
    switch (node.kind) {
        case 'entity':
        case 'label':
            return {
                width: ENTITY_WIDTH,
                height: ENTITY_HEADER_HEIGHT + node.rows.length * ROW_HEIGHT + 8,
            };
        case 'connection':
            return {
                width: CONNECTION_WIDTH,
                height:
                    CONNECTION_HEADER_HEIGHT +
                    node.rows.length * ROW_HEIGHT +
                    (node.rows.length > 0 ? 8 : 0),
            };
        default:
            return assertNever(node.kind);
    }
}

/** Size of the label box drawn on an edge, or null when it has none. */
export function edgeLabelSize(edge: GraphEdge): { width: number; height: number } | null {
    if (edge.label === null && edge.rows.length === 0) return null;
    return {
        width: EDGE_LABEL_WIDTH,
        height:
            EDGE_LABEL_HEADER_HEIGHT +
            edge.rows.length * EDGE_LABEL_ROW_HEIGHT +
            (edge.rows.length > 0 ? 6 : 0),
    };
}

export function layoutGraph(graph: StoreGraph): { nodes: FlowNode[]; edges: FlowEdge[] } {
    // Multigraph: two connections between the same pair of collections are
    // two distinct edges.
    const g = new Graph({ multigraph: true });
    g.setGraph({ rankdir: 'LR', nodesep: 40, ranksep: 100, edgesep: 24, marginx: 16, marginy: 16 });
    g.setDefaultEdgeLabel(() => ({}));

    for (const node of graph.nodes) {
        g.setNode(node.id, nodeSize(node));
    }
    for (const edge of graph.edges) {
        // Giving dagre the label box makes it reserve room for it between ranks.
        const label = edgeLabelSize(edge);
        g.setEdge(edge.source, edge.target, label ? { ...label, labelpos: 'c' } : {}, edge.id);
    }
    layout(g);

    const nodes: FlowNode[] = graph.nodes.map((node) => {
        const { width, height } = nodeSize(node);
        const placed = g.node(node.id) as { x: number; y: number };
        return {
            id: node.id,
            type: node.kind,
            // dagre positions the centre; React Flow the top-left corner.
            position: { x: placed.x - width / 2, y: placed.y - height / 2 },
            width,
            height,
            data: { node },
        };
    });

    const edges: FlowEdge[] = graph.edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        sourceHandle: edge.sourceRow === null ? NODE_HANDLE_OUT : rowHandleOut(edge.sourceRow),
        targetHandle: edge.targetRow === null ? NODE_HANDLE_IN : rowHandleIn(edge.targetRow),
        type: 'schema',
        ...markers(edge.arrow),
        data: { edge },
    }));

    return { nodes, edges };
}

function markers(arrow: EdgeArrow): Pick<FlowEdge, 'markerStart' | 'markerEnd'> {
    const marker = { type: MarkerType.ArrowClosed, width: 16, height: 16 };
    switch (arrow) {
        case 'none':
            return {};
        case 'target':
            return { markerEnd: marker };
        case 'source':
            return { markerStart: marker };
        default:
            return assertNever(arrow);
    }
}
