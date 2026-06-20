import { useQuery } from "@tanstack/react-query";
import { Label } from "@/components/ui/label.tsx";
import { Card } from "@/components/ui/card.tsx";

export default function CollectionView({
    ip,
    port,
    collection,
}: {
    collection: string;
    ip: string;
    port: number;
}) {
    const { isPending, error, data } = useQuery({
        queryKey: ["collection/" + collection],
        queryFn: async () => {
            const response = await fetch(`http://${ip}:${port}/query/take`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({
                    path: [
                        {
                            type: "collection",
                            name: collection,
                            condition: null,
                        },
                    ],
                    take: null,
                    collect: [collection],
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

    const collections: {
        [id: string]: {
            [id: string]: {
                [id: string]: any;
            };
        }[];
    }[] = data.data;

    return (
        <div>
            <Label></Label>
            <div className="flex gap-4">
                {collections.map((item, key) => {
                    return (
                        <Card key={key} className="p-6">
                            {Object.entries(item[collection]).map(
                                ([key, value], index) => {
                                    return (
                                        <Label key={index}>
                                            {key}: {JSON.stringify(value)}
                                        </Label>
                                    );
                                },
                            )}
                        </Card>
                    );
                })}
            </div>
        </div>
    );
}
