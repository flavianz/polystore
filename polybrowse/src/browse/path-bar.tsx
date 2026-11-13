/**
 * Path bar: the query path as breadcrumb chips with filter badges.
 * "×" truncates the path back to that step.
 */
import { Link2, Table2, X } from 'lucide-react';
import type { Filter } from '@/query/condition';
import { describeClause, isFilterEmpty } from '@/query/condition';
import type { PathStep, QueryPath } from '@/query/path';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

export interface PathBarProps {
    readonly path: QueryPath;
    readonly onTruncate: (index: number) => void;
    readonly onSelectStep: (index: number) => void;
    readonly selectedStep: number | null;
}

export function PathBar({ path, onTruncate, onSelectStep, selectedStep }: PathBarProps) {
    return (
        <div className="flex flex-wrap items-center gap-1.5">
            {path.map((step, index) => (
                <div key={`${index}-${stepKey(step)}`} className="flex items-center gap-1.5">
                    {index > 0 && <span className="text-muted-foreground select-none">›</span>}
                    <StepChip
                        step={step}
                        isSelected={selectedStep === index}
                        isLast={index === path.length - 1}
                        onSelect={() => onSelectStep(index)}
                        onTruncate={index > 0 ? () => onTruncate(index - 1) : undefined}
                    />
                </div>
            ))}
        </div>
    );
}

function stepKey(step: PathStep): string {
    return step.kind === 'collection' ? step.name : `${step.connectionName}:${step.collectionName}`;
}

interface StepChipProps {
    readonly step: PathStep;
    readonly isSelected: boolean;
    readonly isLast: boolean;
    readonly onSelect: () => void;
    readonly onTruncate?: () => void;
}

function StepChip({ step, isSelected, isLast, onSelect, onTruncate }: StepChipProps) {
    return (
        <div
            className={cn(
                'flex items-center gap-1 rounded-md border px-2 py-1 text-sm transition-colors',
                isSelected ? 'border-primary bg-primary/10' : 'bg-card hover:bg-accent',
                isLast && !isSelected && 'border-foreground/30',
            )}
        >
            <button
                type="button"
                onClick={onSelect}
                className="flex items-center gap-1.5"
                title="Edit this step's filters"
            >
                {step.kind === 'connection' && (
                    <>
                        <Link2 className="size-3.5 shrink-0" />
                        <span className="text-muted-foreground">{step.connectionName}</span>
                        <FilterBadges filter={step.connectionFilter} />
                        <span className="text-muted-foreground select-none">›</span>
                    </>
                )}
                <Table2 className="size-3.5 shrink-0" />
                <span className="font-medium">
                    {step.kind === 'collection' ? step.name : step.collectionName}
                </span>
                <FilterBadges
                    filter={step.kind === 'collection' ? step.filter : step.collectionFilter}
                />
            </button>
            {onTruncate && (
                <Button
                    variant="ghost"
                    size="icon"
                    className="size-5"
                    onClick={onTruncate}
                    title="Remove this step and everything after it"
                >
                    <X className="size-3" />
                </Button>
            )}
        </div>
    );
}

function FilterBadges({ filter }: { readonly filter: Filter }) {
    if (isFilterEmpty(filter)) return null;

    if (filter.raw !== undefined && filter.raw.trim() !== '') {
        return (
            <Badge variant="secondary" className="font-mono text-xs" title={filter.raw}>
                {filter.raw.length > 28 ? `${filter.raw.slice(0, 27)}…` : filter.raw}
            </Badge>
        );
    }

    return (
        <>
            {filter.clauses.map((clause, index) => (
                <Badge key={index} variant="secondary" className="text-xs">
                    {describeClause(clause)}
                </Badge>
            ))}
        </>
    );
}
