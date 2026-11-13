/**
 * The query path and the top-level view as URL-backed state. No router
 * library: these two are the only things in the URL, so History + popstate is
 * all that's needed.
 */
import { useCallback, useEffect, useState } from 'react';
import type { QueryPath } from './path';
import { decodePath, decodeView, encodeLocation, type AppView } from './url';

export interface QueryPathState {
    /** null = nothing selected yet (show the empty state). */
    readonly path: QueryPath | null;
    readonly view: AppView;
    /** Navigates, pushing a history entry. Keeps the current view. */
    readonly setPath: (path: QueryPath | null) => void;
    /** Replaces the current entry — for edits that should not add history steps. */
    readonly replacePath: (path: QueryPath) => void;
    /** Switches the view, pushing a history entry. Keeps the current path. */
    readonly setView: (view: AppView) => void;
    /** Sets path and view together, as one history entry. */
    readonly setLocation: (path: QueryPath | null, view: AppView) => void;
}

interface LocationState {
    readonly path: QueryPath | null;
    readonly view: AppView;
}

function readLocation(): LocationState {
    return {
        path: decodePath(window.location.search),
        view: decodeView(window.location.search),
    };
}

export function useQueryPath(): QueryPathState {
    const [state, setState] = useState<LocationState>(readLocation);

    useEffect(() => {
        const onPopState = () => setState(readLocation());
        window.addEventListener('popstate', onPopState);
        return () => window.removeEventListener('popstate', onPopState);
    }, []);

    // The callbacks read the other half of the state from the URL itself, so
    // they never act on a stale closure.
    const setPath = useCallback((next: QueryPath | null) => {
        const view = decodeView(window.location.search);
        setState({ path: next, view });
        window.history.pushState(null, '', encodeLocation(next, view));
    }, []);

    const replacePath = useCallback((next: QueryPath) => {
        const view = decodeView(window.location.search);
        setState({ path: next, view });
        window.history.replaceState(null, '', encodeLocation(next, view));
    }, []);

    const setView = useCallback((next: AppView) => {
        const path = decodePath(window.location.search);
        setState({ path, view: next });
        window.history.pushState(null, '', encodeLocation(path, next));
    }, []);

    const setLocation = useCallback((path: QueryPath | null, view: AppView) => {
        setState({ path, view });
        window.history.pushState(null, '', encodeLocation(path, view));
    }, []);

    return { path: state.path, view: state.view, setPath, replacePath, setView, setLocation };
}
