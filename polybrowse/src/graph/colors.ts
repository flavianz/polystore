/**
 * One colour per collection, identical in every store view — the main visual
 * cue that "this table / document / label is that collection".
 *
 * Hues are spread over the sorted collection names, so the assignment is
 * stable across reloads and independent of layout.
 */
import type { DatabaseSchema } from '@/api/types';

export type CollectionColors = ReadonlyMap<string, string>;

export function collectionColors(schema: DatabaseSchema): CollectionColors {
    const names = schema.collections.map((collection) => collection.name).toSorted();
    const colors = new Map<string, string>();
    names.forEach((name, index) => {
        // Golden-angle steps keep neighbouring indices far apart in hue.
        const hue = (25 + index * 137.508) % 360;
        colors.set(name, `oklch(0.68 0.15 ${hue.toFixed(1)})`);
    });
    return colors;
}
