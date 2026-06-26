"use client";

import * as React from "react";

import {
    Sidebar,
    SidebarContent,
    SidebarFooter,
    SidebarGroup,
    SidebarGroupContent,
    SidebarGroupLabel,
    SidebarMenu,
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarMenuSub,
} from "@/components/ui/sidebar";
import { DatabaseZap, FolderIcon, SettingsIcon } from "lucide-react";
import { Label } from "@/components/ui/label.tsx";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button.tsx";
import { Card, CardContent } from "@/components/ui/card.tsx";

export interface CollectionTree {
    [key: string]: CollectionTree | null | undefined;
}

export function AppSidebar({
    collections,
    onSelectedCollection,
    children,
    ip,
    port,
    ...props
}: React.ComponentProps<typeof Sidebar> & {
    collections: CollectionTree;
    onSelectedCollection: (collection: string) => void;
    ip: string;
    port: number;
}) {
    const { isPending, error, data } = useQuery({
        queryKey: ["version"],
        queryFn: () =>
            fetch(`http://${ip}:${port}/version`).then((res) => res.text()),
    });
    return (
        <div className="flex h-screen w-full">
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
                <SidebarFooter>
                    <Card className="p-2 mb-1">
                        <CardContent className="p-2">
                            <SidebarMenu>
                                <SidebarMenuItem className="flex items-center justify-between gap-2">
                                    <DatabaseZap className="h-5 w-5 mr-2" />
                                    <div className="flex-1">
                                        <Label className={"text-md"}>
                                            PolyStore
                                        </Label>
                                        <Label className="text-xs text-muted-foreground">
                                            Version{" "}
                                            {isPending
                                                ? "Loading..."
                                                : error
                                                  ? "Error"
                                                  : data}
                                        </Label>
                                    </div>
                                    <Button variant="outline" size="icon">
                                        <SettingsIcon />
                                    </Button>
                                </SidebarMenuItem>
                            </SidebarMenu>
                        </CardContent>
                    </Card>
                </SidebarFooter>
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
