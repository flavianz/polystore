/**
 * The polybrowse mark: a small network with one highlighted path running
 * diagonally across it, after the graphic on the thesis title page.
 *
 * Dark parts use currentColor so the mark follows the app theme; the path is
 * the title page's teal. public/favicon.svg draws the same geometry — keep the
 * two in sync.
 */
import type { SVGProps } from 'react';

export const LOGO_TEAL = '#14b8b8';

/** Plain nodes and edges, drawn in currentColor. */
const NODES: readonly (readonly [number, number])[] = [
    [16, 4],
    [27, 8],
    [5, 19],
    [13, 26],
    [28, 17],
];
const EDGES: readonly (readonly [number, number, number, number])[] = [
    [5, 6, 16, 4],
    [16, 4, 12, 13],
    [16, 4, 27, 8],
    [27, 8, 20, 14],
    [12, 13, 5, 19],
    [5, 19, 13, 26],
    [13, 26, 21, 21],
    [20, 14, 28, 17],
    [28, 17, 27, 27],
];

/** The highlighted path, top left to bottom right. */
const PATH: readonly (readonly [number, number])[] = [
    [5, 6],
    [12, 13],
    [20, 14],
    [21, 21],
    [27, 27],
];

export function PolybrowseLogo(props: SVGProps<SVGSVGElement>) {
    return (
        <svg viewBox="0 0 32 32" fill="none" aria-hidden="true" {...props}>
            <g stroke="currentColor" strokeWidth={1.25} strokeLinecap="round">
                {EDGES.map(([x1, y1, x2, y2]) => (
                    <line key={`${x1},${y1},${x2},${y2}`} x1={x1} y1={y1} x2={x2} y2={y2} />
                ))}
            </g>
            <polyline
                points={PATH.map(([x, y]) => `${x},${y}`).join(' ')}
                stroke={LOGO_TEAL}
                strokeWidth={1.75}
                strokeLinecap="round"
                strokeLinejoin="round"
            />
            <g fill="currentColor">
                {NODES.map(([cx, cy]) => (
                    <circle key={`${cx},${cy}`} cx={cx} cy={cy} r={2.4} />
                ))}
            </g>
            <g fill={LOGO_TEAL}>
                {PATH.map(([cx, cy]) => (
                    <circle key={`${cx},${cy}`} cx={cx} cy={cy} r={2.6} />
                ))}
            </g>
        </svg>
    );
}
