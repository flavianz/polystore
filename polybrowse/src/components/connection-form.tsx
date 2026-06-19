import { cn } from "@/lib/utils";
import {
    Card,
    CardContent,
    CardDescription,
    CardHeader,
    CardTitle,
} from "@/components/ui/card";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { type ComponentProps, useState } from "react";

export function ConnectionForm({
    className,
    onConnect,
    ...props
}: ComponentProps<"div"> & { onConnect: (ip: string, port: number) => void }) {
    const [ip, setIp] = useState<string>("localhost");
    const [port, setPort] = useState<number | null>(3000);

    function connect() {
        if (port === null) {
            alert("Please enter a port");
            return;
        }
        onConnect(ip, port);
    }

    return (
        <div className={cn("flex flex-col gap-6", className)} {...props}>
            <Card>
                <CardHeader>
                    <CardTitle>Connect</CardTitle>
                    <CardDescription>
                        Connect to a active PolyStore instance
                    </CardDescription>
                </CardHeader>
                <CardContent>
                    <form>
                        <FieldGroup>
                            <Field>
                                <FieldLabel>IP</FieldLabel>
                                <Input
                                    id="ip"
                                    type="text"
                                    value={ip}
                                    onChange={(e) => setIp(e.target.value)}
                                    required
                                />
                            </Field>
                            <Field>
                                <div className="flex items-center">
                                    <FieldLabel>Port</FieldLabel>
                                </div>
                                <Input
                                    id="port"
                                    type="number"
                                    value={port ?? ""}
                                    required
                                    onChange={(e) =>
                                        setPort(
                                            e.target.value === ""
                                                ? null
                                                : Number(e.target.value),
                                        )
                                    }
                                />
                            </Field>
                            <Field>
                                <Button onClick={connect}>Login</Button>
                            </Field>
                        </FieldGroup>
                    </form>
                </CardContent>
            </Card>
        </div>
    );
}
