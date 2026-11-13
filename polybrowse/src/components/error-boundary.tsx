/**
 * Catches render-time exceptions so a bug shows a message instead of a blank
 * page. React only supports class components for this.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
    readonly children: ReactNode;
}

interface ErrorBoundaryState {
    readonly error: Error | null;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
    state: ErrorBoundaryState = { error: null };

    static getDerivedStateFromError(error: Error): ErrorBoundaryState {
        return { error };
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('polybrowse crashed while rendering', error, info.componentStack);
    }

    render() {
        const { error } = this.state;
        if (!error) return this.props.children;

        return (
            <div className="flex min-h-screen items-center justify-center p-6">
                <div className="border-destructive/50 bg-destructive/10 max-w-xl space-y-3 rounded-md border p-4">
                    <h1 className="font-medium">polybrowse hit an error while rendering</h1>
                    <pre className="bg-background max-h-60 overflow-auto rounded border p-2 font-mono text-xs whitespace-pre-wrap">
                        {error.message}
                    </pre>
                    <p className="text-muted-foreground text-sm">
                        The query path lives in the URL, so a bad path can be recovered from by
                        clearing it.
                    </p>
                    <div className="flex gap-2">
                        <button
                            type="button"
                            className="border-input hover:bg-accent rounded-md border px-3 py-1.5 text-sm"
                            onClick={() => this.setState({ error: null })}
                        >
                            Try again
                        </button>
                        <button
                            type="button"
                            className="border-input hover:bg-accent rounded-md border px-3 py-1.5 text-sm"
                            onClick={() => {
                                window.history.replaceState(null, '', window.location.pathname);
                                window.location.reload();
                            }}
                        >
                            Clear the path and reload
                        </button>
                    </div>
                </div>
            </div>
        );
    }
}
