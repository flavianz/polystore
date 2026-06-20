import { useQuery } from "@tanstack/react-query";
import { Label } from "@/components/ui/label.tsx";
import {
    Card,
    CardAction,
    CardContent,
    CardHeader,
    CardTitle,
} from "@/components/ui/card.tsx";
import type { CollectionModel } from "@/pages/Home.tsx";
import { FolderIcon } from "lucide-react";

export interface CollectionPathSegment {
    type: "collection" | "connection";
    name: string;
    condition: string | null;
}

export default function CollectionView({
    ip,
    port,
    collectionPath,
    collections,
}: {
    collectionPath: CollectionPathSegment[];
    ip: string;
    port: number;
    collections: CollectionModel[];
}) {
    const { isPending, error, data } = useQuery({
        queryKey: ["collection/" + collectionPath],
        queryFn: async () => {
            console.log(
                JSON.stringify({
                    path: collectionPath,
                    take: null,
                    collect: collectionPath[collectionPath.length - 1].name,
                }),
            );
            const response = await fetch(`http://${ip}:${port}/query/take`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({
                    path: collectionPath,
                    take: null,
                    collect: [collectionPath[collectionPath.length - 1].name],
                }),
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
            [id: string]: {
                [id: string]: any;
            };
        }[];
    }[] = data.data;

    return (
        <div className={"px-6 pt-6 w-full"}>
            <Card className={"w-full"}>
                <CardHeader>
                    <div className={"flex items-center"}>
                        <FolderIcon className={"mr-2 w-4 h-4"} />
                        <CardTitle>
                            {collectionPath
                                .map((segment) => segment.name)
                                .join(" > ")}
                        </CardTitle>
                    </div>
                    <CardAction>
                        <Label>Fetched {documents.length} documents</Label>
                    </CardAction>
                </CardHeader>
                <CardContent className={""}>
                    <div className="flex gap-4 overflow-x-auto overflow-y-visible">
                        {documents.map((item, key) => {
                            const leafName =
                                collectionPath[collectionPath.length - 1].name;
                            const childCollections =
                                collections.find((col) => col.name === leafName)
                                    ?.childCollections ?? [];
                            return (
                                <Card key={key} className={"m-0.5"}>
                                    <CardContent>
                                        {Object.entries(item[leafName]).map(
                                            ([key, value], index) => {
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
                                                            {value}
                                                        </Label>
                                                    </Label>
                                                );
                                            },
                                        )}
                                    </CardContent>
                                    {childCollections.length > 0 && (
                                        <CardContent>
                                            {childCollections.map((col) => (
                                                <Card className={"p-4"}>
                                                    <CardContent
                                                        className={"p-0"}
                                                    >
                                                        <Label>{col}</Label>
                                                    </CardContent>
                                                </Card>
                                            ))}
                                        </CardContent>
                                    )}
                                </Card>
                            );
                        })}
                    </div>
                </CardContent>
            </Card>
        </div>
    );
}
