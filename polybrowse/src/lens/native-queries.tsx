/**
 * Native queries for the current path: the SQL, Mongo and Cypher each driver
 * would run, side by side, plus the router's choice and a force-driver toggle.
 *
 * Nothing here executes a query — POST /query/explain builds each driver's
 * native query without running it. The one caveat is MongoDB: for paths of
 * three or more segments the driver has no single native query (it issues
 * several finds and joins them in memory), and says so.
 */
import { useExplain } from '@/api/hooks';
import { DRIVER_TYPES, type DriverType } from '@/api/types';
import { tryToRequest, type QueryPath } from '@/query/path';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

const DRIVER_LABELS: Record<DriverType, string> = {
    postgres: 'PostgreSQL',
    mongo: 'MongoDB',
    neo4j: 'Neo4j',
};

export interface NativeQueriesProps {
    readonly path: QueryPath;
    /** null = let the router decide. */
    readonly forceDriver: DriverType | null;
    readonly onForceDriverChange: (driver: DriverType | null) => void;
    /** Which driver actually ran the result currently on screen. */
    readonly executedBy: DriverType | null;
}

export function NativeQueries({
    path,
    forceDriver,
    onForceDriverChange,
    executedBy,
}: NativeQueriesProps) {
    // Skipped while a filter is unserializable; the page reports that itself.
    const result = tryToRequest(path);
    const explainQuery = useExplain('request' in result ? result.request : null);

    return (
        <section className="space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-muted-foreground text-xs font-medium tracking-wide uppercase">
                    Native queries
                </h2>
                <div className="flex items-center gap-1">
                    <span className="text-muted-foreground mr-1 text-xs">run on</span>
                    <Button
                        variant={forceDriver === null ? 'secondary' : 'ghost'}
                        size="sm"
                        className="h-7 text-xs"
                        onClick={() => onForceDriverChange(null)}
                        title="Let the regression model choose"
                    >
                        auto
                    </Button>
                    {DRIVER_TYPES.map((driver) => (
                        <Button
                            key={driver}
                            variant={forceDriver === driver ? 'secondary' : 'ghost'}
                            size="sm"
                            className="h-7 text-xs"
                            onClick={() => onForceDriverChange(driver)}
                        >
                            {driver}
                        </Button>
                    ))}
                </div>
            </div>

            {explainQuery.isPending && <Skeleton className="h-24 w-full" />}
            {explainQuery.isError && (
                <p className="text-destructive text-xs break-words">
                    {explainQuery.error instanceof Error
                        ? explainQuery.error.message
                        : 'explain failed'}
                </p>
            )}

            {explainQuery.data && (
                <>
                    <p className="text-muted-foreground text-xs">
                        The router would pick{' '}
                        <Badge variant="outline" className="font-mono">
                            {explainQuery.data.routedDriver ?? 'none'}
                        </Badge>
                        {forceDriver !== null && (
                            <>
                                {' '}
                                but the result above was forced onto{' '}
                                <Badge variant="outline" className="font-mono">
                                    {executedBy ?? forceDriver}
                                </Badge>
                            </>
                        )}
                        {explainQuery.data.routingError && (
                            <span className="text-destructive">
                                {' '}
                                ({explainQuery.data.routingError})
                            </span>
                        )}
                    </p>

                    <div className="grid gap-2 lg:grid-cols-3">
                        {DRIVER_TYPES.map((driver) => {
                            const queries = explainQuery.data.queries[driver];
                            const isChosen = explainQuery.data.routedDriver === driver;
                            return (
                                <div
                                    key={driver}
                                    className={cn(
                                        'min-w-0 space-y-1 rounded-md border p-2',
                                        isChosen && 'border-primary/60 bg-primary/5',
                                    )}
                                >
                                    <p className="flex items-center gap-1.5 text-xs font-medium">
                                        {DRIVER_LABELS[driver]}
                                        {isChosen && (
                                            <Badge variant="secondary" className="text-xs">
                                                chosen
                                            </Badge>
                                        )}
                                    </p>
                                    {queries === undefined ? (
                                        <p className="text-muted-foreground text-xs">
                                            not connected
                                        </p>
                                    ) : (
                                        queries.map((query, index) => (
                                            <pre
                                                key={index}
                                                className="bg-background overflow-x-auto rounded border p-2 font-mono text-[0.7rem] leading-relaxed"
                                            >
                                                {query}
                                            </pre>
                                        ))
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </>
            )}
        </section>
    );
}
