/**
 * TanStack Query bindings for the polystore API. Components use these rather
 * than calling the client (or fetch) directly.
 */
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/query/keys';
import { api } from './client';
import type {
    DriverType,
    InsertConnectionRequest,
    MappingResponse,
    InsertDocumentRequest,
    QueryRequest,
    UpdateDocumentRequest,
} from './types';

export function useSchema() {
    return useQuery({
        queryKey: queryKeys.schema,
        queryFn: () => api.schema(),
        staleTime: 5 * 60 * 1000,
    });
}

export function useQueryResult(request: QueryRequest | null, forceDriver?: DriverType | null) {
    const effective = request
        ? forceDriver
            ? { ...request, forceDriver }
            : { path: request.path }
        : null;

    return useQuery({
        queryKey: queryKeys.query(effective ?? { path: [] }),
        queryFn: () => api.query(effective!),
        enabled: effective !== null,
    });
}

export function useExplain(request: QueryRequest | null) {
    return useQuery({
        queryKey: queryKeys.explain(request ?? { path: [] }),
        queryFn: () => api.explain(request!),
        enabled: request !== null,
    });
}

export function useRawInstance(collection: string | null, id: string | null) {
    return useQuery({
        queryKey: queryKeys.rawInstance(collection ?? '', id ?? ''),
        queryFn: () => api.rawInstance({ collection: collection!, id: id! }),
        enabled: collection !== null && id !== null,
    });
}

export function useMapping(collection: string | null) {
    return useQuery({
        queryKey: queryKeys.mapping(collection ?? ''),
        queryFn: () => api.mapping(collection!),
        enabled: collection !== null,
        staleTime: 5 * 60 * 1000,
    });
}

export interface MappingsResult {
    /** Every requested mapping by collection name; undefined until all have loaded. */
    readonly data: Readonly<Record<string, MappingResponse>> | undefined;
    readonly isPending: boolean;
    readonly error: Error | null;
}

/**
 * The storage mapping of several collections at once. Shares cache entries
 * with useMapping, so the storage lens and the schema graph fetch each only once.
 */
export function useMappings(collections: readonly string[]): MappingsResult {
    return useQueries({
        queries: collections.map((collection) => ({
            queryKey: queryKeys.mapping(collection),
            queryFn: () => api.mapping(collection),
            staleTime: 5 * 60 * 1000,
        })),
        combine: combineMappings,
    });
}

/**
 * Module-level (stable) so TanStack Query only recomputes it when a result
 * changes — the graph re-layouts whenever this object's identity changes.
 * Keyed by the `collection` each response names itself.
 */
function combineMappings(
    results: readonly { readonly data?: MappingResponse; readonly error: Error | null }[],
): MappingsResult {
    const error = results.find((result) => result.error)?.error ?? null;
    const data: Record<string, MappingResponse> = {};
    for (const result of results) {
        if (result.data === undefined) {
            return { data: undefined, isPending: error === null, error };
        }
        data[result.data.collection] = result.data;
    }
    return { data, isPending: false, error };
}

/**
 * Writes fan out to all three stores server-side, so after one succeeds every
 * cached query result may be stale — invalidate them all. (Demo-sized data;
 * precise invalidation is not worth the bookkeeping.)
 */
function useWriteMutation<TVariables, TResult>(
    mutationFn: (variables: TVariables) => Promise<TResult>,
) {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn,
        onSuccess: () => {
            void queryClient.invalidateQueries({ queryKey: ['query'] });
            void queryClient.invalidateQueries({ queryKey: ['instance-raw'] });
        },
    });
}

export function useInsertDocument() {
    return useWriteMutation((body: InsertDocumentRequest) => api.insertDocument(body));
}

export function useUpdateDocument() {
    return useWriteMutation((body: UpdateDocumentRequest) => api.updateDocument(body));
}

export function useInsertConnection() {
    return useWriteMutation((body: InsertConnectionRequest) => api.insertConnection(body));
}
