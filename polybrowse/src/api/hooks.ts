/**
 * TanStack Query bindings for the polystore API. Components use these rather
 * than calling the client (or fetch) directly.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/query/keys';
import { api } from './client';
import type {
    DriverType,
    InsertConnectionRequest,
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
