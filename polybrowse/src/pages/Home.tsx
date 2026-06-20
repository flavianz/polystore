import { useQuery } from "@tanstack/react-query";
import { AppSidebar, type CollectionTree } from "@/components/app-sidebar.tsx";
import { useState } from "react";
import { Label } from "@/components/ui/label.tsx";
import CollectionView from "@/components/collection-view.tsx";

export default function Home({ ip, port }: { ip: string; port: number }) {
    const [collection, setCollection] = useState<string | null>(null);

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
            onSelectedCollection={(collection) => setCollection(collection)}
        >
            {!collection ? (
                <div className="place-items-center justify-center flex">
                    <Label>No collection selected</Label>
                </div>
            ) : (
                <CollectionView
                    collection={collection}
                    ip={ip}
                    port={port}
                ></CollectionView>
            )}
        </AppSidebar>
    );
}

interface CollectionModel {
    name: string;
    schema: {
        [id: string]: string;
    };
    childCollections?: string[];
    parentCollection?: string;
}
