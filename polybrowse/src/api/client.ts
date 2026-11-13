/**
 * The single typed entry point to the polystore HTTP API.
 * Components must not call fetch directly.
 *
 * In dev, requests go to relative paths and Vite proxies them to Ktor on :8080
 * (see vite.config.ts → server.proxy).
 *
 * Note on content types: the query/inspection routes respond via
 * `call.respond(HttpStatusCode.OK, someJsonString)`, which Ktor sends as
 * text/plain. `Response.json()` parses the body regardless of its header, so
 * that is safe — but the write routes genuinely return prose ("Inserted
 * document with UUID ..."), which is why those use `requestText`.
 */
import type {
    DatabaseSchema,
    ExplainResponse,
    InsertConnectionRequest,
    InsertDocumentRequest,
    MappingResponse,
    QueryRequest,
    QueryResponse,
    RawInstanceRequest,
    RawInstanceResponse,
    UpdateDocumentRequest,
} from './types';

export class PolystoreError extends Error {
    // Declared as fields rather than constructor parameter properties, which
    // `erasableSyntaxOnly` forbids.
    readonly status: number;
    readonly body: string;
    readonly route: string;

    constructor(status: number, body: string, route: string) {
        super(`${route} failed (${status}): ${body || 'no response body'}`);
        this.name = 'PolystoreError';
        this.status = status;
        this.body = body;
        this.route = route;
    }
}

async function request(route: string, init?: RequestInit): Promise<Response> {
    const response = await fetch(route, init);
    if (!response.ok) {
        throw new PolystoreError(response.status, await response.text().catch(() => ''), route);
    }
    return response;
}

async function requestJson<T>(route: string, init?: RequestInit): Promise<T> {
    const response = await request(route, init);
    return (await response.json()) as T;
}

async function requestText(route: string, init?: RequestInit): Promise<string> {
    return (await request(route, init)).text();
}

function postJson(body: unknown): RequestInit {
    return {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    };
}

export const api = {
    version: () => requestText('/version'),

    /** GET /schema — collections and connections, each carrying its PolySchema. */
    schema: () => requestJson<DatabaseSchema>('/schema'),

    /** POST /query/get — runs the query (optionally forcing a driver). */
    query: (body: QueryRequest) => requestJson<QueryResponse>('/query/get', postJson(body)),

    /** POST /query/explain — native query per driver + routing decision, nothing executed. */
    explain: (body: QueryRequest) => requestJson<ExplainResponse>('/query/explain', postJson(body)),

    /** POST /instance/raw — the raw stored representation in each store. */
    rawInstance: (body: RawInstanceRequest) =>
        requestJson<RawInstanceResponse>('/instance/raw', postJson(body)),

    /** GET /schema/mapping/{collection} — poly field → physical location per store. */
    mapping: (collection: string) =>
        requestJson<MappingResponse>(`/schema/mapping/${encodeURIComponent(collection)}`),

    /** POST /document/insert — returns the new instance's UUID. */
    insertDocument: async (body: InsertDocumentRequest): Promise<string> => {
        const message = await requestText('/document/insert', postJson(body));
        const uuid = message.replace('Inserted document with UUID ', '').trim();
        if (!uuid) {
            throw new PolystoreError(200, message, '/document/insert');
        }
        return uuid;
    },

    /** POST /document/update — scalar schema fields only; dynamic data is not updatable. */
    updateDocument: (body: UpdateDocumentRequest) =>
        requestText('/document/update', postJson(body)),

    /** POST /connection/insert */
    insertConnection: (body: InsertConnectionRequest) =>
        requestText('/connection/insert', postJson(body)),
};
