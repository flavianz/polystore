"use client";

import * as React from "react";

import {
    Sidebar,
    SidebarContent,
    SidebarGroup,
    SidebarGroupContent,
    SidebarGroupLabel,
    SidebarMenu,
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarMenuSub,
    SidebarRail,
} from "@/components/ui/sidebar";
import { FolderIcon } from "lucide-react";

export interface CollectionTree {
    [key: string]: CollectionTree | null | undefined;
}

export function AppSidebar({
    collections,
    onSelectedCollection,
    children,
    ...props
}: React.ComponentProps<typeof Sidebar> & {
    collections: CollectionTree;
    onSelectedCollection: (collection: string) => void;
}) {
    return (
        <div className="flex">
            <Sidebar {...props}>
                <SidebarContent>
                    <SidebarGroup>
                        <SidebarGroupLabel>Collections</SidebarGroupLabel>
                        <SidebarGroupContent>
                            <SidebarMenu>
                                {Object.entries(collections).map(
                                    (child, index) => {
                                        const [name, children] = child;
                                        return (
                                            <Tree
                                                key={index}
                                                name={name}
                                                children={children}
                                                onSelectedCollection={
                                                    onSelectedCollection
                                                }
                                            />
                                        );
                                    },
                                )}
                            </SidebarMenu>
                        </SidebarGroupContent>
                    </SidebarGroup>
                </SidebarContent>
                <SidebarRail />
            </Sidebar>
            {children}
        </div>
    );
}

function Tree({
    name,
    children,
    onSelectedCollection,
}: {
    name: string;
    children: CollectionTree | null | undefined;
    onSelectedCollection: (collection: string) => void;
}) {
    if (!children) {
        return (
            <SidebarMenuButton
                className="data-[active=true]:bg-transparent"
                onClick={() => onSelectedCollection(name)}
            >
                <FolderIcon />
                {name}
            </SidebarMenuButton>
        );
    }

    return (
        <SidebarMenuItem>
            <SidebarMenuButton
                onClick={() => onSelectedCollection(name)}
                className="data-[active=true]:bg-transparent"
            >
                <FolderIcon />
                {name}
            </SidebarMenuButton>
            <SidebarMenuSub>
                {Object.entries(children).map((child, index) => {
                    const [name, children] = child;
                    return (
                        <Tree
                            key={index}
                            name={name}
                            children={children}
                            onSelectedCollection={onSelectedCollection}
                        />
                    );
                })}
            </SidebarMenuSub>
        </SidebarMenuItem>
    );
}
