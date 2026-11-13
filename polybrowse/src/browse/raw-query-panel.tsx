/**
 * Raw query panel: the request JSON, editable and two-way synced with the path
 * bar. Edits are validated against the wire types before they can be applied,
 * so the panel can never put the path into a state the server would reject.
 */
import { useState } from 'react';
import { AlertCircle, Check, Code2 } from 'lucide-react';
import { tryToRequest, type QueryPath } from '@/query/path';
import { parseWireRequest } from '@/query/wire';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

export interface RawQueryPanelProps {
    readonly path: QueryPath;
    readonly onApply: (path: QueryPath) => void;
}

export function RawQueryPanel({ path, onApply }: RawQueryPanelProps) {
    const result = tryToRequest(path);
    // While a filter is incomplete there is no JSON to show; keep the panel
    // mounted (so the textarea is not remounted mid-edit) and say so.
    const serialized =
        'request' in result
            ? JSON.stringify(result.request, null, 2)
            : `// filter incomplete: ${result.error}`;

    const [draft, setDraft] = useState(serialized);
    const [syncedFrom, setSyncedFrom] = useState(serialized);

    // The path changed elsewhere (a chip, the sidebar, the filter editor), so
    // pull it in. Adjusting state during render is the documented way to react
    // to changed props without an extra render pass.
    if (serialized !== syncedFrom) {
        setSyncedFrom(serialized);
        setDraft(serialized);
    }

    const isDirty = draft !== serialized;
    const parsed = isDirty ? parseWireRequest(draft) : null;
    const error = parsed && 'error' in parsed ? parsed.error : null;

    return (
        <section className="space-y-2">
            <div className="flex items-center justify-between gap-2">
                <h2 className="text-muted-foreground flex items-center gap-1.5 text-xs font-medium tracking-wide uppercase">
                    <Code2 className="size-3.5" />
                    Request JSON
                </h2>
                <div className="flex items-center gap-2">
                    {isDirty && (
                        <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 text-xs"
                            onClick={() => setDraft(serialized)}
                        >
                            Reset
                        </Button>
                    )}
                    <Button
                        size="sm"
                        className="h-7 text-xs"
                        disabled={!isDirty || error !== null}
                        onClick={() => {
                            if (parsed && 'path' in parsed) onApply(parsed.path);
                        }}
                    >
                        <Check className="mr-1 size-3" />
                        Apply
                    </Button>
                </div>
            </div>

            <Textarea
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                className="min-h-[9rem] font-mono text-xs"
                spellCheck={false}
                aria-invalid={error !== null}
                aria-label="Request JSON"
            />

            {error && (
                <p className="text-destructive flex items-start gap-1.5 text-xs">
                    <AlertCircle className="mt-0.5 size-3.5 shrink-0" />
                    <span className="break-words">{error}</span>
                </p>
            )}
            {isDirty && !error && (
                <p className="text-muted-foreground text-xs">
                    Valid — apply to navigate. Conditions you type here are kept verbatim as DSL.
                </p>
            )}
        </section>
    );
}
