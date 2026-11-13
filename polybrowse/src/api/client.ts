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
    PolyInputValue,
    PolySchema,
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
    return postText(JSON.stringify(body));
}

function postText(json: string): RequestInit {
    return {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: json,
    };
}

/**
 * Serializes a write body whose `fields` follow `schema`.
 *
 * The server parses a JSON number as Int whenever it is integral
 * (`JsonElement.toPolyValue`, RequestDefinitions.kt), and `DataType.matchesType`
 * accepts only Float/Double for FLOAT — so `"price": 10` is rejected with
 * "does not contain all required fields". `JSON.stringify(10.0)` yields `10`,
 * hence FLOAT fields holding a whole number are written as `10.0` by hand.
 */
function postWriteJson(
    body: { readonly fields: Record<string, PolyInputValue> },
    schema: PolySchema,
): RequestInit {
    const { fields, ...rest } = body;
    const fieldsJson = Object.entries(fields)
        .map(([field, value]) => {
            const isWholeFloat =
                schema[field] === 'FLOAT' && typeof value === 'number' && Number.isSafeInteger(value);
            return `${JSON.stringify(field)}:${isWholeFloat ? `${value}.0` : JSON.stringify(value)}`;
        })
        .join(',');
    const restJson = JSON.stringify(rest).slice(1, -1);
    return postText(`{${restJson}${restJson ? ',' : ''}"fields":{${fieldsJson}}}`);
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
    insertDocument: async (body: InsertDocumentRequest, schema: PolySchema): Promise<string> => {
        const message = await requestText('/document/insert', postWriteJson(body, schema));
        const uuid = message.replace('Inserted document with UUID ', '').trim();
        if (!uuid) {
            throw new PolystoreError(200, message, '/document/insert');
        }
        return uuid;
    },

    /** POST /document/update — scalar schema fields only; dynamic data is not updatable. */
    updateDocument: (body: UpdateDocumentRequest, schema: PolySchema) =>
        requestText('/document/update', postWriteJson(body, schema)),

    /** POST /connection/insert — `schema` is the connection's connectionDataSchema. */
    insertConnection: (body: InsertConnectionRequest, schema: PolySchema) =>
        requestText('/connection/insert', postWriteJson(body, schema)),
};
