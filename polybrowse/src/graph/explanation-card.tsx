/**
 * The "How is this stored?" card for the selected poly element.
 */
import { Fragment } from 'react';
import { Table2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { assertNever } from '@/lib/assert-never';
import type { Explanation, PhysicalStore } from './explain';

const STORES: readonly PhysicalStore[] = ['postgres', 'mongo', 'neo4j'];

function storeName(store: PhysicalStore): string {
    switch (store) {
        case 'postgres':
            return 'PostgreSQL';
        case 'mongo':
            return 'MongoDB';
        case 'neo4j':
            return 'Neo4j';
        default:
            return assertNever(store);
    }
}

export interface ExplanationCardProps {
    readonly explanation: Explanation;
    readonly onClose: () => void;
    readonly onBrowse: (collection: string) => void;
}

export function ExplanationCard({ explanation, onClose, onBrowse }: ExplanationCardProps) {
    const browse = explanation.browseCollection;
    return (
        <div className="bg-card text-card-foreground absolute right-3 bottom-3 z-10 max-h-[70%] w-[400px] max-w-[calc(100%-1.5rem)] overflow-y-auto rounded-lg border p-4 text-sm shadow-lg">
            <div className="mb-2 flex items-start gap-2">
                <div className="min-w-0 flex-1">
                    <p className="text-muted-foreground text-xs">How is this stored?</p>
                    <p className="truncate font-mono font-semibold">{explanation.title}</p>
                </div>
                <Button variant="ghost" size="icon-xs" onClick={onClose} aria-label="Close">
                    <X />
                </Button>
            </div>

            <p className="mb-3">
                <CodeText text={explanation.poly} />
            </p>

            <dl className="space-y-2.5">
                {STORES.map((store) => (
                    <div key={store}>
                        <dt className="text-muted-foreground mb-0.5 text-xs font-medium tracking-wide uppercase">
                            {storeName(store)}
                        </dt>
                        {explanation.stores[store].map((line) => (
                            <dd key={line} className="leading-snug">
                                <CodeText text={line} />
                            </dd>
                        ))}
                    </div>
                ))}
            </dl>

            {browse && (
                <Button
                    variant="outline"
                    size="sm"
                    className="mt-3"
                    onClick={() => onBrowse(browse)}
                >
                    <Table2 />
                    Browse <span className="font-mono">{browse}</span>
                </Button>
            )}
        </div>
    );
}

/** Renders `backticked` spans as code. */
function CodeText({ text }: { readonly text: string }) {
    return (
        <>
            {text.split('`').map((part, index) =>
                index % 2 === 1 ? (
                    <code
                        key={index}
                        className="bg-muted rounded px-1 py-0.5 font-mono text-[0.85em] break-all"
                    >
                        {part}
                    </code>
                ) : (
                    <Fragment key={index}>{part}</Fragment>
                ),
            )}
        </>
    );
}
