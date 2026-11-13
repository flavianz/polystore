/**
 * TanStack Query keys. The query path *is* the cache key: the request object
 * produced by toRequest() is structurally hashed by TanStack Query, so two
 * paths that serialize identically share a cache entry.
 */
import type { QueryRequest } from '@/api/types';

export const queryKeys = {
    version: ['version'] as const,
    schema: ['schema'] as const,
    query: (request: QueryRequest) => ['query', request] as const,
    explain: (request: QueryRequest) => ['explain', request] as const,
    rawInstance: (collection: string, id: string) => ['instance-raw', collection, id] as const,
    mapping: (collection: string) => ['mapping', collection] as const,
};
