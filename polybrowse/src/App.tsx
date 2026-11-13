import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowsePage } from '@/browse/browse-page';
import { ErrorBoundary } from '@/components/error-boundary';

// Retry is off: a failed query is usually a malformed path or a store that is
// down, and retrying just delays the error the user needs to see.
const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export default function App() {
    return (
        <ErrorBoundary>
            <QueryClientProvider client={queryClient}>
                <BrowsePage />
            </QueryClientProvider>
        </ErrorBoundary>
    );
}
