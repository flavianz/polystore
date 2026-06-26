import { useQuery } from "@tanstack/react-query";
import { AppSidebar, type CollectionTree } from "@/components/app-sidebar.tsx";
import { useState } from "react";
import { Label } from "@/components/ui/label.tsx";
import QueryView, {
    type QuerySegment,
    type TakeQuery,
} from "@/components/query-view.tsx";
import { Card, CardContent } from "@/components/ui/card";
import { ChevronRightIcon } from "lucide-react";

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
            fetch(`http://${ip}:${port}/schema`).then((res) => res.json()),
    });

    if (isPending) return "Loading...";

    if (error) {
        console.error("Error fetching collections:", error);
        return "An error has occurred: " + error.message;
    }

    const schema = data as DatabaseSchema;

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
                const child = schema.collections.find(
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
    for (const collection of schema.collections.filter(
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
            ip={ip}
            port={port}
        >
            {!query ? (
                <div className="place-items-center justify-center flex">
                    <Label>No collection selected</Label>
                </div>
            ) : (
                <div className={"px-6 pt-6 w-full"}>
                    <Card className={"mb-6"}>
                        <CardContent className={"flex items-center"}>
                            {query.path.map((segment, index) => {
                                return (
                                    <>
                                        <Card className={"mx-2 py-2"}>
                                            <CardContent>
                                                {" "}
                                                {segment.name}
                                            </CardContent>
                                        </Card>
                                        {index !== query.path.length - 1 && (
                                            <ChevronRightIcon
                                                className={"h-4 w-4"}
                                            />
                                        )}
                                    </>
                                );
                            })}
                        </CardContent>
                    </Card>
                    <QueryView
                        query={query}
                        ip={ip}
                        port={port}
                        schema={schema}
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
                        onSelectedConnection={(
                            parentCollection,
                            parentDocUuid,
                            connection,
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
                                type: "connection",
                                name: connection.name,
                                condition: null,
                            });
                            queryPath.push({
                                type: "collection",
                                name:
                                    parentCollection ==
                                    connection.collection1Name
                                        ? connection.collection2Name
                                        : connection.collection1Name,
                                condition: null,
                            });

                            setQuery({
                                path: queryPath,
                                collect: [
                                    ...(query.collect ?? []).filter((segment) =>
                                        remainingSegmentNames.includes(segment),
                                    ),
                                    connection.name,
                                    parentCollection ==
                                    connection.collection1Name
                                        ? connection.collection2Name
                                        : connection.collection1Name,
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

export interface DatabaseSchema {
    collections: CollectionModel[];
    connections: ConnectionModel[];
}

export interface CollectionModel {
    name: string;
    schema: {
        [id: string]: string;
    };
    childCollections?: string[];
    parentCollection?: string;
}

export interface ConnectionModel {
    name: string;
    collection1Name: string;
    collection2Name: string;
    connectionDataSchema: {
        [id: string]: string;
    };
}
