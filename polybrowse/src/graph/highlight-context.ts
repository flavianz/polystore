/**
 * Linked highlighting: the mapping keys currently hovered, shared by every
 * graph pane on the page so that hovering in one lights up the others.
 */
import { createContext, useContext } from 'react';
import { keyId, type MappingKey } from './mapping-key';
import type { CollectionColors } from './colors';

export interface GraphContextValue {
    /** keyId()s of the hovered element; null = nothing hovered. */
    readonly hovered: ReadonlySet<string> | null;
    readonly setHovered: (keys: readonly MappingKey[] | null) => void;
    readonly colors: CollectionColors;
}

export const GraphContext = createContext<GraphContextValue>({
    hovered: null,
    setHovered: () => undefined,
    colors: new Map(),
});

export function useGraphContext(): GraphContextValue {
    return useContext(GraphContext);
}

/**
 * 'active'  — the element carries a hovered key.
 * 'dimmed'  — something is hovered, and it is not this.
 * 'idle'    — nothing is hovered.
 */
export type HighlightState = 'active' | 'dimmed' | 'idle';

export function highlightOf(
    hovered: ReadonlySet<string> | null,
    keys: readonly MappingKey[],
): HighlightState {
    if (hovered === null) return 'idle';
    return keys.some((key) => hovered.has(keyId(key))) ? 'active' : 'dimmed';
}
