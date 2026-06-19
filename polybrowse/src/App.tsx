import "./App.css";
import { QueryClientProvider, QueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ConnectionForm } from "./components/connection-form.tsx";
import { Button } from "@/components/ui/button";
import Home from "./pages/Home.tsx";

const queryClient = new QueryClient();

export default function App() {
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

    return (
        <QueryClientProvider client={queryClient}>
            <Home ip={ip} port={port} />
        </QueryClientProvider>
    );
}
