/**
 * Linked highlighting: the mapping keys currently hovered or selected, shared
 * by every graph pane on the page so that hovering in one lights up the others.
 */
import { createContext, useContext } from 'react';
import { keyId, type MappingKey } from './mapping-key';
import type { CollectionColors } from './colors';

export interface GraphContextValue {
    /**
     * keyId()s to light up: the hovered element's, or else the selected one's;
     * null = nothing highlighted.
     */
    readonly highlighted: ReadonlySet<string> | null;
    readonly setHovered: (keys: readonly MappingKey[] | null) => void;
    /** Pins an element (shows its explanation); null clears. */
    readonly select: (key: MappingKey | null) => void;
    readonly colors: CollectionColors;
}

export const GraphContext = createContext<GraphContextValue>({
    highlighted: null,
    setHovered: () => undefined,
    select: () => undefined,
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
    highlighted: ReadonlySet<string> | null,
    keys: readonly MappingKey[],
): HighlightState {
    if (highlighted === null) return 'idle';
    return keys.some((key) => highlighted.has(keyId(key))) ? 'active' : 'dimmed';
}
