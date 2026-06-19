import { useQuery } from "@tanstack/react-query";

export default function Home({ ip, port }: { ip: string; port: number }) {
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

    return (
        <div>
            {(data as CollectionModel[]).map((collection) => (
                <div>
                    {collection.name} parent{" "}
                    {collection.parentCollection ?? "no parent"}
                </div>
            ))}
        </div>
    );
}

interface CollectionModel {
    name: string;
    schema: {
        [id: string]: string;
    };
    childCollections: string[];
    parentCollection: string | null;
}
