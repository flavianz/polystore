import "./App.css";
import { QueryClientProvider, QueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ConnectionForm } from "./components/connection-form.tsx";
import { Button } from "@/components/ui/button";

const queryClient = new QueryClient();

function App() {
    const [ip, setIp] = useState<string | null>(null);
    const [port, setPort] = useState<number | null>(null);
    const [connected, setConnected] = useState<boolean>(false);
    const [loading, setLoading] = useState<boolean>(false);

    if (!ip || !port) {
        return (
            <div className="w-screen h-screen flex place-items-center justify-center">
                <ConnectionForm
                    className="w-1/3 "
                    onConnect={(ip, port) => {
                        setIp(ip);
                        setPort(port);
                    }}
                />
            </div>
        );
    }

    if (!connected) {
        return (
            <div className="w-screen h-screen flex place-items-center justify-center">
                <Button
                    className="bg-primary text-primary-foreground px-4 py-2 rounded-4xl"
                    onClick={() => {}}
                >
                    Connect
                </Button>
            </div>
        );
    }

    return (
        <QueryClientProvider client={queryClient}>
            <p>
                Interface {ip} {port}
            </p>
        </QueryClientProvider>
    );
}

export default App;
