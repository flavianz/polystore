import { useQuery } from "@tanstack/react-query";
import { Label } from "@/components/ui/label.tsx";
import {
    Card,
    CardContent,
    CardHeader,
    CardTitle,
} from "@/components/ui/card.tsx";
import type { CollectionModel } from "@/pages/Home.tsx";
import { FolderIcon, Link2Icon } from "lucide-react";
import { Separator } from "@/components/ui/separator.tsx";

export default function QueryView({
    ip,
    port,
    query,
    collections,
    onSelectedSubCollection,
}: {
    query: TakeQuery;
    ip: string;
    port: number;
    collections: CollectionModel[];
    onSelectedSubCollection?: (
        parentCollection: string,
        parentDocUuid: string,
        subCollectionName: string,
    ) => void;
}) {
    const { isPending, error, data } = useQuery({
        queryKey: ["collection/" + JSON.stringify(query)],
        queryFn: async () => {
            const response = await fetch(`http://${ip}:${port}/query/take`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify(query),
            });
            return response.json();
        },
    });

    if (isPending) return "Loading...";

    if (error) {
        console.error("Error fetching collections:", error);
        return "An error has occurred: " + error.message;
    }

    const documents: {
        [id: string]: {
            [key: string]: never;
        };
    }[] = data.data;

    if (documents.length == 0) {
        return (
            <div className={"flex items-center justify-center"}>
                <Label>Empty Response</Label>
            </div>
        );
    }

    return (
        <div className={"px-6 pt-6 w-full"}>
            {documents.map((doc, index) => {
                const sortedEntries = query.path.map((segment) => {
                    return (
                        Object.entries(doc).find(
                            ([key]) => key === segment.name,
                        ) ?? []
                    );
                });
                return (
                    <Card key={index} className={"mb-4"}>
                        <CardContent className={"flex gap-4"}>
                            {sortedEntries.map(
                                ([segmentName, data], index2) => {
                                    const childCollections =
                                        collections.find(
                                            (col) => col.name === segmentName,
                                        )?.childCollections ?? [];
                                    return (
                                        <>
                                            <Card
                                                key={index2}
                                                className={"flex-1"}
                                            >
                                                <CardHeader
                                                    className={
                                                        "flex items-center"
                                                    }
                                                >
                                                    {data["_id"] ===
                                                    undefined ? (
                                                        <Link2Icon
                                                            className={
                                                                "h-4 w-4"
                                                            }
                                                        />
                                                    ) : (
                                                        <FolderIcon
                                                            className={
                                                                "h-4 w-4"
                                                            }
                                                        />
                                                    )}
                                                    <CardTitle>
                                                        {segmentName}
                                                    </CardTitle>
                                                </CardHeader>
                                                <CardContent>
                                                    {Object.entries(data).map(
                                                        (
                                                            [key, value],
                                                            index,
                                                        ) => {
                                                            return (
                                                                <Label
                                                                    className="text-muted-foreground font-mono"
                                                                    key={index}
                                                                >
                                                                    {key}:{" "}
                                                                    <Label
                                                                        className={
                                                                            "text-primary-foreground"
                                                                        }
                                                                    >
                                                                        {JSON.stringify(
                                                                            value,
                                                                        )}
                                                                    </Label>
                                                                </Label>
                                                            );
                                                        },
                                                    )}
                                                    {childCollections.length >
                                                        0 && (
                                                        <Separator
                                                            className={"my-3"}
                                                        />
                                                    )}
                                                    {childCollections.map(
                                                        (childCollection) => {
                                                            return (
                                                                <Card
                                                                    className={
                                                                        "py-4 hover:cursor-pointer"
                                                                    }
                                                                    onClick={() => {
                                                                        if (
                                                                            onSelectedSubCollection
                                                                        ) {
                                                                            onSelectedSubCollection(
                                                                                segmentName,
                                                                                data[
                                                                                    "_id"
                                                                                ],
                                                                                childCollection,
                                                                            );
                                                                        }
                                                                    }}
                                                                >
                                                                    <CardContent
                                                                        className={
                                                                            "hover:cursor-pointer"
                                                                        }
                                                                    >
                                                                        <Label
                                                                            className={
                                                                                " hover:cursor-pointer"
                                                                            }
                                                                        >
                                                                            {
                                                                                childCollection
                                                                            }
                                                                        </Label>
                                                                    </CardContent>
                                                                </Card>
                                                            );
                                                        },
                                                    )}
                                                </CardContent>
                                            </Card>
                                            {index2 + 1 !==
                                                Object.entries(doc).length && (
                                                <Label
                                                    className={
                                                        "text-muted-foreground"
                                                    }
                                                >
                                                    {">"}
                                                </Label>
                                            )}
                                        </>
                                    );
                                },
                            )}
                        </CardContent>
                    </Card>
                );
            })}
        </div>
    );
}

export interface TakeQuery {
    path: QuerySegment[];
    take: {
        [key: string]: string[];
    } | null;
    collect: string[] | null;
}

export interface QuerySegment {
    type: "collection" | "connection";
    name: string;
    condition: string | null;
}
