/**
 * Deterministic layered layout (dagre, left → right) of a StoreGraph, turned
 * into React Flow nodes and edges. The same schema always yields the same
 * picture, which matters when it is shown on a slide.
 *
 * Node sizes are computed from row counts and must agree with the fixed
 * dimensions used by the node components (see nodes.tsx).
 */
import { Graph, layout } from '@dagrejs/dagre';
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import { assertNever } from '@/lib/assert-never';
import type { GraphEdge, GraphNode, StoreGraph } from './store-graph';

export const ENTITY_WIDTH = 248;
export const ENTITY_HEADER_HEIGHT = 48;
export const ROW_HEIGHT = 24;
export const CONNECTION_WIDTH = 184;
export const CONNECTION_HEADER_HEIGHT = 32;

// Type aliases (not interfaces) so they satisfy React Flow's Record constraint.
export type GraphNodeData = { readonly node: GraphNode };
export type GraphEdgeData = { readonly edge: GraphEdge };
export type FlowNode = Node<GraphNodeData, GraphNode['kind']>;
export type FlowEdge = Edge<GraphEdgeData>;

export function nodeSize(node: GraphNode): { width: number; height: number } {
    switch (node.kind) {
        case 'entity':
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

export function layoutGraph(graph: StoreGraph): { nodes: FlowNode[]; edges: FlowEdge[] } {
    const g = new Graph();
    g.setGraph({ rankdir: 'LR', nodesep: 36, ranksep: 90, marginx: 16, marginy: 16 });
    g.setDefaultEdgeLabel(() => ({}));

    for (const node of graph.nodes) {
        g.setNode(node.id, nodeSize(node));
    }
    for (const edge of graph.edges) {
        g.setEdge(edge.source, edge.target);
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
        type: 'smoothstep',
        label: edge.label ?? undefined,
        markerEnd: edge.directed ? { type: MarkerType.ArrowClosed } : undefined,
        data: { edge },
    }));

    return { nodes, edges };
}
