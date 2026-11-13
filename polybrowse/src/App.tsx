import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowsePage } from '@/browse/browse-page';

// Retry is off: a failed query is usually a malformed path or a store that is
// down, and retrying just delays the error the user needs to see.
const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export default function App() {
    return (
        <QueryClientProvider client={queryClient}>
            <BrowsePage />
        </QueryClientProvider>
    );
}
