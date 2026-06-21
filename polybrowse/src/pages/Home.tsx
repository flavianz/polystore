import { useQuery } from "@tanstack/react-query";
import { AppSidebar, type CollectionTree } from "@/components/app-sidebar.tsx";
import { useState } from "react";
import { Label } from "@/components/ui/label.tsx";
import QueryView, {
    type QuerySegment,
    type TakeQuery,
} from "@/components/query-view.tsx";

export default function Home({ ip, port }: { ip: string; port: number }) {
    const [query, setQuery] = useState<TakeQuery | null>({
        path: [
            { type: "collection", name: "hospitals", condition: null },
            { type: "collection", name: "departments", condition: null },
            { type: "collection", name: "doctors", condition: null },
            { type: "connection", name: "treatments", condition: null },
            { type: "collection", name: "patients", condition: null },
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
    console.log(query);

    return (
        <AppSidebar
            collections={tree}
            onSelectedCollection={(collection) => {
                setQuery({
                    path: [
                        {
                            type: "collection",
                            name: collection,
                            condition: null,
                        },
                    ],
                    collect: [collection],
                    take: null,
                });
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
                            parentCollection,
                            parentDocUuid,
                            collectionName,
                        ) => {
                            const queryPath = [...query.path].slice(
                                0,
                                query.path.findIndex(
                                    (segment: QuerySegment) =>
                                        segment.name == parentCollection,
                                ) + 1,
                            );
                            if (queryPath.length > 0) {
                                queryPath[queryPath.length - 1].condition =
                                    `_id == ${parentDocUuid}`;
                            }
                            const remainingSegmentNames = queryPath.map(
                                (segment) => segment.name,
                            );
                            queryPath.push({
                                type: "collection",
                                name: collectionName,
                                condition: null,
                            });
                            console.log(remainingSegmentNames, query.collect);

                            setQuery({
                                path: queryPath,
                                collect: [
                                    ...(query.collect ?? []).filter((segment) =>
                                        remainingSegmentNames.includes(segment),
                                    ),
                                    collectionName,
                                ],
                                take: Object.fromEntries(
                                    Object.entries(query.take ?? {}).filter(
                                        ([segmentName]) =>
                                            segmentName in
                                            remainingSegmentNames,
                                    ),
                                ),
                            });
                        }}
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
