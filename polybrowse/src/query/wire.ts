/**
 * Validation of hand-edited request JSON (the raw query panel).
 *
 * The text is untrusted, so this validates the wire shape from
 * server/RequestDefinitions.kt before it can become a QueryPath — the panel
 * reports the problem rather than letting the server 500.
 */
import type { RequestQuerySegment } from '@/api/types';
import { duplicateNames, fromRequestSegments, type QueryPath } from './path';

export type WireParseResult = { readonly path: QueryPath } | { readonly error: string };

export function parseWireRequest(text: string): WireParseResult {
    let parsed: unknown;
    try {
        parsed = JSON.parse(text);
    } catch (error) {
        return { error: error instanceof Error ? error.message : 'invalid JSON' };
    }

    if (!isRecord(parsed)) return { error: 'expected an object with a "path" array' };
    if (!Array.isArray(parsed.path)) return { error: '"path" must be an array of segments' };
    if (parsed.path.length === 0) return { error: '"path" cannot be empty' };

    const segments: RequestQuerySegment[] = [];
    for (const [index, candidate] of parsed.path.entries()) {
        const segment = parseSegment(candidate, index);
        if ('error' in segment) return segment;
        segments.push(segment.segment);
    }

    const path = fromRequestSegments(segments);
    if (!path) {
        return {
            error: 'invalid path: it must start with a collection, and every connection segment must be followed by a collection segment',
        };
    }

    const duplicates = duplicateNames(path);
    if (duplicates.length > 0) {
        return {
            error: `${duplicates.join(', ')} appears more than once; a collection or connection may appear only once in a query`,
        };
    }

    return { path };
}

type SegmentResult = { readonly segment: RequestQuerySegment } | { readonly error: string };

function parseSegment(value: unknown, index: number): SegmentResult {
    const at = `path[${index}]`;
    if (!isRecord(value)) return { error: `${at} must be an object` };

    if (typeof value.name !== 'string' || value.name === '') {
        return { error: `${at}.name must be a non-empty string` };
    }
    if (value.type !== 'collection' && value.type !== 'connection') {
        return { error: `${at}.type must be "collection" or "connection"` };
    }

    // The Kotlin fields are non-optional (nullable), so a missing key fails
    // deserialization server-side — treat it as an error here too.
    if (!('condition' in value)) return { error: `${at}.condition is required (use null)` };
    if (value.condition !== null && typeof value.condition !== 'string') {
        return { error: `${at}.condition must be a string or null` };
    }
    if (!('only' in value)) return { error: `${at}.only is required (use null)` };
    if (value.only !== null && !isStringArray(value.only)) {
        return { error: `${at}.only must be an array of strings or null` };
    }

    return {
        segment: {
            name: value.name,
            type: value.type,
            condition: value.condition,
            only: value.only,
        },
    };
}

function isStringArray(value: unknown): value is string[] {
    return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
