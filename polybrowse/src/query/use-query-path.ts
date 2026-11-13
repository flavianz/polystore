/**
 * The query path as URL-backed state. No router library: the path is the only
 * thing in the URL, so History + popstate is all that's needed.
 */
import { useCallback, useEffect, useState } from 'react';
import type { QueryPath } from './path';
import { decodePath, encodePath } from './url';

export interface QueryPathState {
    /** null = nothing selected yet (show the empty state). */
    readonly path: QueryPath | null;
    /** Navigates, pushing a history entry. */
    readonly setPath: (path: QueryPath | null) => void;
    /** Replaces the current entry — for edits that should not add history steps. */
    readonly replacePath: (path: QueryPath) => void;
}

export function useQueryPath(): QueryPathState {
    const [path, setPathState] = useState<QueryPath | null>(() =>
        decodePath(window.location.search),
    );

    useEffect(() => {
        const onPopState = () => setPathState(decodePath(window.location.search));
        window.addEventListener('popstate', onPopState);
        return () => window.removeEventListener('popstate', onPopState);
    }, []);

    const setPath = useCallback((next: QueryPath | null) => {
        setPathState(next);
        window.history.pushState(
            null,
            '',
            next === null ? window.location.pathname : encodePath(next),
        );
    }, []);

    const replacePath = useCallback((next: QueryPath) => {
        setPathState(next);
        window.history.replaceState(null, '', encodePath(next));
    }, []);

    return { path, setPath, replacePath };
}
