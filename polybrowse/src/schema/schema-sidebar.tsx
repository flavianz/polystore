/**
 * Schema sidebar: a tree of the PolySchema. Clicking a collection starts a new
 * path; clicking a subcollection or connection under the *current* collection
 * appends a segment to the existing one.
 */
import { useState } from 'react';
import { ChevronRight, Database, Hash, Link2, Table2, Type } from 'lucide-react';
import type { CollectionModel, DatabaseSchema, DataType } from '@/api/types';
import {
    Sidebar,
    SidebarContent,
    SidebarGroup,
    SidebarGroupLabel,
    SidebarHeader,
    SidebarMenu,
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarMenuSub,
    SidebarMenuSubButton,
    SidebarMenuSubItem,
} from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { childCollections, connectionsOf, rootCollections } from './model';

export interface SchemaSidebarProps {
    readonly schema: DatabaseSchema | undefined;
    readonly isLoading: boolean;
    /** The collection the current path points at, for highlighting. */
    readonly currentCollection: string | null;
    readonly onSelectCollection: (collectionName: string) => void;
    readonly onAppendSubcollection: (collectionName: string) => void;
    readonly onAppendConnection: (connectionName: string, targetCollection: string) => void;
}

export function SchemaSidebar({
    schema,
    isLoading,
    currentCollection,
    onSelectCollection,
    onAppendSubcollection,
    onAppendConnection,
}: SchemaSidebarProps) {
    return (
        <Sidebar>
            <SidebarHeader>
                <div className="flex items-center gap-2 px-2 py-1.5">
                    <Database className="size-4 shrink-0" />
                    <span className="font-semibold">polybrowse</span>
                </div>
            </SidebarHeader>
            <SidebarContent>
                <SidebarGroup>
                    <SidebarGroupLabel>Collections</SidebarGroupLabel>
                    <SidebarMenu>
                        {isLoading && (
                            <div className="space-y-2 px-2 py-1">
                                <Skeleton className="h-6 w-full" />
                                <Skeleton className="h-6 w-4/5" />
                                <Skeleton className="h-6 w-3/5" />
                            </div>
                        )}
                        {schema &&
                            rootCollections(schema).map((collection) => (
                                <CollectionNode
                                    key={collection.name}
                                    schema={schema}
                                    collection={collection}
                                    depth={0}
                                    currentCollection={currentCollection}
                                    onSelectCollection={onSelectCollection}
                                    onAppendSubcollection={onAppendSubcollection}
                                    onAppendConnection={onAppendConnection}
                                />
                            ))}
                        {schema && schema.collections.length === 0 && (
                            <p className="text-muted-foreground px-2 py-1 text-sm">
                                The polystore has no collections yet.
                            </p>
                        )}
                    </SidebarMenu>
                </SidebarGroup>
            </SidebarContent>
        </Sidebar>
    );
}

interface CollectionNodeProps {
    readonly schema: DatabaseSchema;
    readonly collection: CollectionModel;
    readonly depth: number;
    readonly currentCollection: string | null;
    readonly onSelectCollection: (collectionName: string) => void;
    readonly onAppendSubcollection: (collectionName: string) => void;
    readonly onAppendConnection: (connectionName: string, targetCollection: string) => void;
}

function CollectionNode({
    schema,
    collection,
    depth,
    currentCollection,
    onSelectCollection,
    onAppendSubcollection,
    onAppendConnection,
}: CollectionNodeProps) {
    const [isOpen, setIsOpen] = useState(depth === 0);
    const children = childCollections(schema, collection.name);
    const connections = connectionsOf(schema, collection.name);
    const fields = Object.entries(collection.schema);
    const isCurrent = currentCollection === collection.name;

    return (
        <SidebarMenuItem>
            <div className="flex items-center">
                <button
                    type="button"
                    aria-label={isOpen ? 'Collapse' : 'Expand'}
                    onClick={() => setIsOpen((open) => !open)}
                    className="hover:bg-sidebar-accent flex size-6 shrink-0 items-center justify-center rounded"
                >
                    <ChevronRight
                        className={cn('size-3.5 transition-transform', isOpen && 'rotate-90')}
                    />
                </button>
                <SidebarMenuButton
                    isActive={isCurrent}
                    onClick={() => onSelectCollection(collection.name)}
                    tooltip="Start a new path here"
                    className="flex-1"
                >
                    <Table2 className="size-4" />
                    <span className="truncate">{collection.name}</span>
                </SidebarMenuButton>
            </div>

            {isOpen && (
                <SidebarMenuSub>
                    {fields.map(([field, type]) => (
                        <SidebarMenuSubItem key={field}>
                            <div className="text-muted-foreground flex items-center gap-2 px-2 py-1 text-xs">
                                <FieldIcon type={type} />
                                <span className="truncate">{field}</span>
                                <span className="ml-auto shrink-0 opacity-70">{type}</span>
                            </div>
                        </SidebarMenuSubItem>
                    ))}

                    {children.map((child) => (
                        <SidebarMenuSubItem key={child.name}>
                            <SidebarMenuSubButton
                                onClick={() =>
                                    isCurrent
                                        ? onAppendSubcollection(child.name)
                                        : onSelectCollection(child.name)
                                }
                                title={
                                    isCurrent
                                        ? `Append subcollection ${child.name} to the path`
                                        : `Subcollection of ${collection.name}`
                                }
                            >
                                <Table2 className="size-3.5" />
                                <span className="truncate">{child.name}</span>
                            </SidebarMenuSubButton>
                        </SidebarMenuSubItem>
                    ))}

                    {connections.map(({ connection, otherCollection }) => (
                        <SidebarMenuSubItem key={connection.name}>
                            {/* Traversing a connection only makes sense from the
                                collection the path currently points at. */}
                            <SidebarMenuSubButton
                                aria-disabled={!isCurrent}
                                className={cn(!isCurrent && 'pointer-events-none opacity-50')}
                                onClick={
                                    isCurrent
                                        ? () => onAppendConnection(connection.name, otherCollection)
                                        : undefined
                                }
                                title={
                                    isCurrent
                                        ? `Append connection ${connection.name} → ${otherCollection}`
                                        : `Select ${collection.name} first to traverse this connection`
                                }
                            >
                                <Link2 className="size-3.5" />
                                <span className="truncate">{connection.name}</span>
                                <span className="text-muted-foreground ml-auto shrink-0 text-xs">
                                    → {otherCollection}
                                </span>
                            </SidebarMenuSubButton>
                        </SidebarMenuSubItem>
                    ))}

                    {/* Nested subcollections get their own expandable node. */}
                    {children.some((child) => childCollections(schema, child.name).length > 0) &&
                        children
                            .filter((child) => childCollections(schema, child.name).length > 0)
                            .map((child) => (
                                <CollectionNode
                                    key={`nested-${child.name}`}
                                    schema={schema}
                                    collection={child}
                                    depth={depth + 1}
                                    currentCollection={currentCollection}
                                    onSelectCollection={onSelectCollection}
                                    onAppendSubcollection={onAppendSubcollection}
                                    onAppendConnection={onAppendConnection}
                                />
                            ))}
                </SidebarMenuSub>
            )}
        </SidebarMenuItem>
    );
}

function FieldIcon({ type }: { readonly type: DataType }) {
    switch (type) {
        case 'INT':
        case 'FLOAT':
            return <Hash className="size-3" />;
        default:
            return <Type className="size-3" />;
    }
}
