/**
 * Result table. One row per matched path combination (that is what the server
 * returns — a joined row), with the columns grouped by path segment so it is
 * clear which value came from which step.
 */
import { useMemo } from 'react';
import {
    createColumnHelper,
    flexRender,
    tableFeatures,
    useTable,
    type ColumnDef,
} from '@tanstack/react-table';
import type { PolyFieldValue, PolyRow } from '@/api/types';
import {
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableHeader,
    TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';

export interface ResultTableProps {
    readonly rows: readonly PolyRow[];
    /** Segment names in path order; the last one is emphasized. */
    readonly segmentOrder: readonly string[];
    readonly selectedRow: number | null;
    readonly onSelectRow: (index: number) => void;
}

// No sorting/filtering/pagination features: the result table renders exactly
// what the server returned (paging and virtualization are out of scope).
const features = tableFeatures({});
type Features = typeof features;

const columnHelper = createColumnHelper<Features, PolyRow>();

export function ResultTable({
    rows,
    segmentOrder,
    selectedRow,
    onSelectRow,
}: ResultTableProps) {
    const columns = useMemo(() => buildColumns(rows, segmentOrder), [rows, segmentOrder]);

    const table = useTable({
        features,
        data: rows as PolyRow[],
        columns,
    });

    if (rows.length === 0) {
        return (
            <div className="text-muted-foreground flex h-32 items-center justify-center text-sm">
                No instances match this query.
            </div>
        );
    }

    return (
        <div className="overflow-x-auto rounded-md border">
            <Table>
                <TableHeader>
                    {table.getHeaderGroups().map((headerGroup) => (
                        <TableRow key={headerGroup.id}>
                            {headerGroup.headers.map((header) => (
                                <TableHead
                                    key={header.id}
                                    colSpan={header.colSpan}
                                    className={cn(
                                        'whitespace-nowrap',
                                        header.depth === 1 && 'text-foreground font-semibold',
                                    )}
                                >
                                    {header.isPlaceholder
                                        ? null
                                        : flexRender(
                                              header.column.columnDef.header,
                                              header.getContext(),
                                          )}
                                </TableHead>
                            ))}
                        </TableRow>
                    ))}
                </TableHeader>
                <TableBody>
                    {table.getRowModel().rows.map((row) => (
                        <TableRow
                            key={row.id}
                            onClick={() => onSelectRow(row.index)}
                            data-state={selectedRow === row.index ? 'selected' : undefined}
                            className="cursor-pointer"
                        >
                            {/* getAllCells, not getVisibleCells: column
                                visibility is not one of the enabled features. */}
                            {row.getAllCells().map((cell) => (
                                <TableCell key={cell.id} className="whitespace-nowrap">
                                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                                </TableCell>
                            ))}
                        </TableRow>
                    ))}
                </TableBody>
            </Table>
        </div>
    );
}

/**
 * Columns are derived from the rows rather than the schema: `only` projections
 * and dynamic (non-schema) fields mean the response is the only authority on
 * which fields actually came back.
 */
function buildColumns(
    rows: readonly PolyRow[],
    segmentOrder: readonly string[],
): ColumnDef<Features, PolyRow, unknown>[] {
    const fieldsBySegment = new Map<string, Set<string>>();

    for (const row of rows) {
        for (const [segment, fields] of Object.entries(row)) {
            const known = fieldsBySegment.get(segment) ?? new Set<string>();
            for (const field of Object.keys(fields)) {
                known.add(field);
            }
            fieldsBySegment.set(segment, known);
        }
    }

    // Keep path order, then append any segment the response had but the path did not name.
    const segments = [
        ...segmentOrder.filter((segment) => fieldsBySegment.has(segment)),
        ...[...fieldsBySegment.keys()].filter((segment) => !segmentOrder.includes(segment)),
    ];

    return segments.map((segment) => {
        const fields = [...(fieldsBySegment.get(segment) ?? [])].toSorted(compareFields);
        return columnHelper.group({
            id: segment,
            header: segment,
            columns: fields.map((field) =>
                columnHelper.display({
                    id: `${segment}.${field}`,
                    header: field,
                    cell: (context) => <ValueCell value={context.row.original[segment]?.[field]} />,
                }),
            ),
        });
    }) as ColumnDef<Features, PolyRow, unknown>[];
}

/** _id first, then alphabetical. */
function compareFields(a: string, b: string): number {
    if (a === '_id') return -1;
    if (b === '_id') return 1;
    return a.localeCompare(b);
}

function ValueCell({ value }: { readonly value: PolyFieldValue | undefined }) {
    if (value === undefined || value === null) {
        return <span className="text-muted-foreground italic">null</span>;
    }
    if (typeof value === 'boolean') {
        return <span className="font-mono">{value ? 'true' : 'false'}</span>;
    }
    if (typeof value === 'number') {
        return <span className="font-mono tabular-nums">{value}</span>;
    }
    if (isUuid(value)) {
        return (
            <span className="font-mono text-xs" title={value}>
                {value.slice(0, 8)}…
            </span>
        );
    }
    return <span>{value}</span>;
}

function isUuid(value: string): boolean {
    return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(
        value,
    );
}
