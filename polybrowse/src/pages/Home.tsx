import { useQuery } from "@tanstack/react-query";
import { AppSidebar, type CollectionTree } from "@/components/app-sidebar.tsx";
import { useState } from "react";
import { Label } from "@/components/ui/label.tsx";
import QueryView, { type TakeQuery } from "@/components/query-view.tsx";

export default function Home({ ip, port }: { ip: string; port: number }) {
    const [query, setQuery] = useState<TakeQuery | null>({
        path: [
            { type: "collection", name: "hospitals", condition: null },
            { type: "collection", name: "departments", condition: null },
            { type: "collection", name: "doctors", condition: null },
            { type: "connection", name: "treatments", condition: null },
            { type: "collection", name: "patients", condition: null },
            /*{
                type: "connection",
                connectionName: "treatments",
                connectionCondition: null,
                collectionName: "patients",
                collectionCondition: null,
            },*/
        ],
        collect: [
            "hospitals",
            "departments",
            "doctors",
            "treatments",
            "patients",
        ],
        take: null,
    });

    const { isPending, error, data } = useQuery({
        queryKey: ["collections"],
        queryFn: () =>
            fetch(`http://${ip}:${port}/collections/list`).then((res) =>
                res.json(),
            ),
    });

    if (isPending) return "Loading...";

    if (error) {
        console.error("Error fetching collections:", error);
        return "An error has occurred: " + error.message;
    }

    const collections = data as CollectionModel[];

    function buildCollectionTree(
        collection: CollectionModel,
    ): CollectionTree | null {
        if (
            !collection.childCollections ||
            collection.childCollections.length === 0
        ) {
            return null;
        } else {
            const childCollections: CollectionTree = {};
            for (const childName of collection.childCollections) {
                const child = collections.find(
                    (item) => item.name === childName,
                );
                if (child) {
                    childCollections[childName] = buildCollectionTree(child);
                }
            }
            return childCollections;
        }
    }

    const tree: CollectionTree = {};
    for (const collection of collections.filter(
        (collection) => !collection.parentCollection,
    )) {
        tree[collection.name] = buildCollectionTree(collection);
    }

    return (
        <AppSidebar
            collections={tree}
            onSelectedCollection={(collection) => {
                setSegmentPath([
                    {
                        type: "collection",
                        name: collection,
                        condition: null,
                    },
                ]);
                console.log("p", JSON.stringify(segmentPath));
            }}
        >
            {!query ? (
                <div className="place-items-center justify-center flex">
                    <Label>No collection selected</Label>
                </div>
            ) : (
                <div className={"w-full"}>
                    <QueryView
                        query={query}
                        ip={ip}
                        port={port}
                        collections={collections}
                        onSelectedSubCollection={(
                            parentDocUuid,
                            collection,
                        ) => {}}
                    />
                    <div className={"h-6"} />
                </div>
            )}
        </AppSidebar>
    );
}

export interface CollectionModel {
    name: string;
    schema: {
        [id: string]: string;
    };
    childCollections?: string[];
    parentCollection?: string;
}
