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
    ...props
}: React.ComponentProps<typeof Sidebar> & { collections: CollectionTree }) {
    return (
        <Sidebar {...props}>
            <SidebarContent>
                <SidebarGroup>
                    <SidebarGroupLabel>Collections</SidebarGroupLabel>
                    <SidebarGroupContent>
                        <SidebarMenu>
                            {Object.entries(collections).map((child, index) => {
                                const [name, children] = child;
                                return (
                                    <Tree
                                        key={index}
                                        name={name}
                                        children={children}
                                    />
                                );
                            })}
                        </SidebarMenu>
                    </SidebarGroupContent>
                </SidebarGroup>
            </SidebarContent>
            <SidebarRail />
        </Sidebar>
    );
}

function Tree({
    name,
    children,
}: {
    name: string;
    children: CollectionTree | null | undefined;
}) {
    if (!children) {
        return (
            <SidebarMenuButton className="data-[active=true]:bg-transparent">
                <FolderIcon />
                {name}
            </SidebarMenuButton>
        );
    }

    return (
        <SidebarMenuItem>
            <SidebarMenuButton
                onClick={() => {
                    alert(name);
                }}
                className="data-[active=true]:bg-transparent"
            >
                <FolderIcon />
                {name}
            </SidebarMenuButton>
            <SidebarMenuSub>
                {Object.entries(children).map((child, index) => {
                    const [name, children] = child;
                    return <Tree key={index} name={name} children={children} />;
                })}
            </SidebarMenuSub>
        </SidebarMenuItem>
    );
}
