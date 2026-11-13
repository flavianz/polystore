/**
 * Storage lens for one instance: how the same data is physically laid out in
 * PostgreSQL, MongoDB and Neo4j.
 *
 * The field-mapping table deliberately shows all three stores side by side
 * rather than behind tabs, because the point of the lens is the comparison —
 * hovering a poly field highlights its physical home in each store, and
 * hovering a physical key highlights the poly field it belongs to.
 */
import { useState } from 'react';
import { useMapping, useRawInstance } from '@/api/hooks';
import type { MappingResponse, RawInstanceResponse } from '@/api/types';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';

export interface StorageLensProps {
    readonly collection: string;
    readonly instanceId: string;
}

export function StorageLens({ collection, instanceId }: StorageLensProps) {
    const mappingQuery = useMapping(collection);
    const rawQuery = useRawInstance(collection, instanceId);

    // The poly field currently hovered anywhere in the lens; drives highlighting
    // in every store's view at once.
    const [hoveredField, setHoveredField] = useState<string | null>(null);

    if (mappingQuery.isPending || rawQuery.isPending) {
        return (
            <div className="space-y-2">
                <Skeleton className="h-6 w-full" />
                <Skeleton className="h-24 w-full" />
            </div>
        );
    }

    const error = mappingQuery.error ?? rawQuery.error;
    if (error) {
        return (
            <p className="text-destructive text-xs break-words">
                {error instanceof Error ? error.message : 'could not load storage details'}
            </p>
        );
    }
    if (!mappingQuery.data || !rawQuery.data) return null;

    return (
        <div className="space-y-4">
            <PhysicalSummary mapping={mappingQuery.data} />
            <FieldMapping
                mapping={mappingQuery.data}
                hoveredField={hoveredField}
                onHoverField={setHoveredField}
            />
            <RawData
                raw={rawQuery.data}
                mapping={mappingQuery.data}
                hoveredField={hoveredField}
                onHoverField={setHoveredField}
            />
        </div>
    );
}

function PhysicalSummary({ mapping }: { readonly mapping: MappingResponse }) {
    return (
        <section className="space-y-1.5">
            <SectionTitle>Where this collection lives</SectionTitle>
            <dl className="space-y-1 text-xs">
                <StoreRow label="PostgreSQL">
                    table <code className="font-mono">{mapping.postgres.table}</code>
                    {mapping.postgres.parentTable && (
                        <>
                            , child of{' '}
                            <code className="font-mono">{mapping.postgres.parentTable}</code> via{' '}
                            <code className="font-mono">{mapping.postgres.parentFkColumn}</code>
                        </>
                    )}
                </StoreRow>
                <StoreRow label="MongoDB">
                    collection <code className="font-mono">{mapping.mongo.collection}</code>
                    {mapping.mongo.embeddedInParentAs && (
                        <>
                            , also embedded in{' '}
                            <code className="font-mono">{mapping.mongo.parentCollection}</code>{' '}
                            under{' '}
                            <code className="font-mono">{mapping.mongo.embeddedInParentAs}</code>
                        </>
                    )}
                </StoreRow>
                <StoreRow label="Neo4j">
                    label <code className="font-mono">{mapping.neo4j.label}</code>
                    {mapping.neo4j.parentRelationship && (
                        <>
                            , linked from its parent by{' '}
                            <code className="font-mono">{mapping.neo4j.parentRelationship}</code>
                        </>
                    )}
                </StoreRow>
            </dl>

            {Object.keys(mapping.postgres.connectionTables).length > 0 && (
                <div className="space-y-1 pt-1">
                    <SectionTitle>Connections</SectionTitle>
                    {Object.entries(mapping.neo4j.connections).map(([name, neo4j]) => {
                        const postgres = mapping.postgres.connectionTables[name];
                        const mongoKey = mapping.mongo.connectionArrays[name];
                        return (
                            <div key={name} className="space-y-0.5 text-xs">
                                <p className="font-mono">
                                    {name}{' '}
                                    <Badge variant="outline" className="ml-1 text-xs">
                                        {neo4j.direction}
                                    </Badge>{' '}
                                    <span className="text-muted-foreground">
                                        → {neo4j.otherCollection}
                                    </span>
                                </p>
                                <p className="text-muted-foreground pl-3">
                                    pg: join table{' '}
                                    <code className="font-mono">{postgres?.table}</code> (
                                    <code className="font-mono">
                                        {postgres?.fkColumnThisSide}
                                    </code>{' '}
                                    ↔{' '}
                                    <code className="font-mono">
                                        {postgres?.fkColumnOtherSide}
                                    </code>
                                    ) · mongo: <code className="font-mono">{mongoKey}</code> ·
                                    neo4j: <code className="font-mono">
                                        :{neo4j.relationshipType}
                                    </code>
                                </p>
                            </div>
                        );
                    })}
                </div>
            )}
        </section>
    );
}

interface FieldMappingProps {
    readonly mapping: MappingResponse;
    readonly hoveredField: string | null;
    readonly onHoverField: (field: string | null) => void;
}

function FieldMapping({ mapping, hoveredField, onHoverField }: FieldMappingProps) {
    const fields = Object.keys(mapping.postgres.fields);

    return (
        <section className="space-y-1.5">
            <SectionTitle>Field mapping</SectionTitle>
            <div className="overflow-x-auto">
                <table className="w-full text-xs">
                    <thead className="text-muted-foreground">
                        <tr className="text-left">
                            <th className="py-1 pr-2 font-medium">poly field</th>
                            <th className="py-1 pr-2 font-medium">pg column</th>
                            <th className="py-1 pr-2 font-medium">mongo key</th>
                            <th className="py-1 font-medium">neo4j property</th>
                        </tr>
                    </thead>
                    <tbody className="font-mono">
                        <tr
                            onMouseEnter={() => onHoverField('_id')}
                            onMouseLeave={() => onHoverField(null)}
                            className={cn(hoveredField === '_id' && 'bg-primary/15')}
                        >
                            <td className="py-0.5 pr-2">_id</td>
                            <td className="py-0.5 pr-2">{mapping.postgres.idColumn}</td>
                            <td className="py-0.5 pr-2">{mapping.mongo.idKey}</td>
                            <td className="py-0.5">{mapping.neo4j.idProperty}</td>
                        </tr>
                        {fields.map((field) => (
                            <tr
                                key={field}
                                onMouseEnter={() => onHoverField(field)}
                                onMouseLeave={() => onHoverField(null)}
                                className={cn(hoveredField === field && 'bg-primary/15')}
                            >
                                <td className="py-0.5 pr-2">
                                    {field}
                                    <span className="text-muted-foreground ml-1">
                                        {mapping.postgres.fields[field]?.type}
                                    </span>
                                </td>
                                <td className="py-0.5 pr-2">
                                    {mapping.postgres.fields[field]?.column}
                                </td>
                                <td className="py-0.5 pr-2">{mapping.mongo.fields[field]?.key}</td>
                                <td className="py-0.5">
                                    {mapping.neo4j.fields[field]?.property}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            <p className="text-muted-foreground text-xs">
                Non-schema (&ldquo;dynamic&rdquo;) fields are not listed: PostgreSQL keeps them in{' '}
                <code className="font-mono">{mapping.postgres.dynamicDataColumn}</code>, while
                MongoDB and Neo4j store them like any other field.
            </p>
        </section>
    );
}

interface RawDataProps {
    readonly raw: RawInstanceResponse;
    readonly mapping: MappingResponse;
    readonly hoveredField: string | null;
    readonly onHoverField: (field: string | null) => void;
}

function RawData({ raw, mapping, hoveredField, onHoverField }: RawDataProps) {
    return (
        <section className="space-y-1.5">
            <SectionTitle>Raw stored data</SectionTitle>
            <Tabs defaultValue="postgres">
                <TabsList className="grid w-full grid-cols-3">
                    <TabsTrigger value="postgres" className="text-xs">
                        PostgreSQL
                    </TabsTrigger>
                    <TabsTrigger value="mongo" className="text-xs">
                        MongoDB
                    </TabsTrigger>
                    <TabsTrigger value="neo4j" className="text-xs">
                        Neo4j
                    </TabsTrigger>
                </TabsList>

                <TabsContent value="postgres" className="space-y-2 pt-2">
                    {raw.postgres === null ? (
                        <NotActive store="PostgreSQL" />
                    ) : (
                        Object.entries(raw.postgres.tables).map(([table, rows]) => (
                            <div key={table} className="space-y-1">
                                <p className="text-muted-foreground font-mono text-xs">
                                    {table}{' '}
                                    <span className="not-italic">
                                        ({rows.length} row{rows.length === 1 ? '' : 's'})
                                    </span>
                                </p>
                                {rows.map((row, index) => (
                                    <KeyValueBlock
                                        key={index}
                                        entries={Object.entries(row)}
                                        // pg column names equal the poly field names
                                        fieldFor={(key) => polyFieldForPgColumn(mapping, key)}
                                        hoveredField={hoveredField}
                                        onHoverField={onHoverField}
                                    />
                                ))}
                            </div>
                        ))
                    )}
                </TabsContent>

                <TabsContent value="mongo" className="space-y-2 pt-2">
                    {raw.mongo === null ? (
                        <NotActive store="MongoDB" />
                    ) : (
                        <>
                            {raw.mongo.document ? (
                                <KeyValueBlock
                                    entries={Object.entries(raw.mongo.document)}
                                    fieldFor={(key) => polyFieldForMongoKey(mapping, key)}
                                    hoveredField={hoveredField}
                                    onHoverField={onHoverField}
                                />
                            ) : (
                                <p className="text-muted-foreground text-xs">
                                    No document in its own collection.
                                </p>
                            )}
                            {raw.mongo.parentDocument && (
                                <div className="space-y-1">
                                    <p className="text-muted-foreground text-xs">
                                        Also embedded in the parent document under{' '}
                                        <code className="font-mono">
                                            {mapping.mongo.embeddedInParentAs}
                                        </code>
                                        :
                                    </p>
                                    <KeyValueBlock
                                        entries={Object.entries(raw.mongo.parentDocument)}
                                        fieldFor={() => null}
                                        hoveredField={hoveredField}
                                        onHoverField={onHoverField}
                                    />
                                </div>
                            )}
                        </>
                    )}
                </TabsContent>

                <TabsContent value="neo4j" className="space-y-2 pt-2">
                    {raw.neo4j === null ? (
                        <NotActive store="Neo4j" />
                    ) : raw.neo4j.node === null ? (
                        <p className="text-muted-foreground text-xs">No node found.</p>
                    ) : (
                        <>
                            <p className="text-muted-foreground font-mono text-xs">
                                {raw.neo4j.node.labels.map((label) => `:${label}`).join('')}
                            </p>
                            <KeyValueBlock
                                entries={Object.entries(raw.neo4j.node.properties)}
                                fieldFor={(key) => polyFieldForNeo4jProperty(mapping, key)}
                                hoveredField={hoveredField}
                                onHoverField={onHoverField}
                            />
                            {raw.neo4j.relationships.length > 0 && (
                                <div className="space-y-1">
                                    <p className="text-muted-foreground text-xs">
                                        {raw.neo4j.relationships.length} relationship
                                        {raw.neo4j.relationships.length === 1 ? '' : 's'}:
                                    </p>
                                    {raw.neo4j.relationships.map((relationship, index) => (
                                        <div
                                            key={index}
                                            className="bg-background rounded border p-2 font-mono text-xs"
                                        >
                                            <span>
                                                {relationship.direction === 'incoming' ? '←' : '→'}{' '}
                                                :{relationship.type}
                                            </span>{' '}
                                            <span className="text-muted-foreground">
                                                {relationship.otherLabels
                                                    .map((label) => `:${label}`)
                                                    .join('')}
                                            </span>
                                            {Object.entries(relationship.properties).length > 0 && (
                                                <span className="text-muted-foreground">
                                                    {' '}
                                                    {JSON.stringify(relationship.properties)}
                                                </span>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </TabsContent>
            </Tabs>
        </section>
    );
}

interface KeyValueBlockProps {
    readonly entries: readonly [string, unknown][];
    /** Maps a physical key back to its poly field, for linked highlighting. */
    readonly fieldFor: (key: string) => string | null;
    readonly hoveredField: string | null;
    readonly onHoverField: (field: string | null) => void;
}

function KeyValueBlock({ entries, fieldFor, hoveredField, onHoverField }: KeyValueBlockProps) {
    return (
        <div className="bg-background divide-y rounded border font-mono text-xs">
            {entries.map(([key, value]) => {
                const field = fieldFor(key);
                const isHighlighted = field !== null && field === hoveredField;
                return (
                    <div
                        key={key}
                        onMouseEnter={() => field && onHoverField(field)}
                        onMouseLeave={() => field && onHoverField(null)}
                        className={cn(
                            'grid grid-cols-[8rem_1fr] items-start gap-2 px-2 py-1',
                            isHighlighted && 'bg-primary/15',
                        )}
                    >
                        <span className="text-muted-foreground truncate" title={key}>
                            {key}
                        </span>
                        <span className="min-w-0 break-words">{renderValue(value)}</span>
                    </div>
                );
            })}
        </div>
    );
}

function renderValue(value: unknown) {
    if (value === null || value === undefined) {
        return <span className="text-muted-foreground italic">null</span>;
    }
    if (typeof value === 'object') {
        return <span className="text-muted-foreground">{JSON.stringify(value)}</span>;
    }
    return String(value);
}

function StoreRow({
    label,
    children,
}: {
    readonly label: string;
    readonly children: React.ReactNode;
}) {
    return (
        <div className="grid grid-cols-[5.5rem_1fr] items-start gap-2">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="min-w-0 break-words">{children}</dd>
        </div>
    );
}

function SectionTitle({ children }: { readonly children: React.ReactNode }) {
    return (
        <h3 className="text-muted-foreground text-xs font-medium tracking-wide uppercase">
            {children}
        </h3>
    );
}

function NotActive({ store }: { readonly store: string }) {
    return <p className="text-muted-foreground text-xs">{store} is not connected.</p>;
}

// ── physical key → poly field ─────────────────────────────────────────────

function polyFieldForPgColumn(mapping: MappingResponse, column: string): string | null {
    if (column === mapping.postgres.idColumn) return '_id';
    const match = Object.entries(mapping.postgres.fields).find(
        ([, location]) => location.column === column,
    );
    return match?.[0] ?? null;
}

function polyFieldForMongoKey(mapping: MappingResponse, key: string): string | null {
    if (key === mapping.mongo.idKey) return '_id';
    const match = Object.entries(mapping.mongo.fields).find(([, location]) => location.key === key);
    return match?.[0] ?? null;
}

function polyFieldForNeo4jProperty(mapping: MappingResponse, property: string): string | null {
    if (property === mapping.neo4j.idProperty) return '_id';
    const match = Object.entries(mapping.neo4j.fields).find(
        ([, location]) => location.property === property,
    );
    return match?.[0] ?? null;
}
