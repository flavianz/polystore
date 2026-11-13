/**
 * Exhaustiveness check for unions. Put it in the `default` branch of a switch
 * over a discriminated union: if a new variant is added to the union (e.g.
 * because a Kotlin sealed class gained a subclass), this stops compiling
 * instead of silently falling through.
 */
export function assertNever(value: never): never {
    throw new Error(`unhandled variant: ${JSON.stringify(value)}`);
}
