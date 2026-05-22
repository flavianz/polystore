package ch.flavianz.query

import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment

class QueryBuilder {
    private var path: QueryPath? = null
    private var terminal: PolyTerminal? = null

    fun from(vararg steps: QuerySegment) {
        path = QueryPath(steps.toList())
    }

    fun take(vararg fields: FieldRef) {
        check(terminal == null) { "Terminal already set" }
        terminal = PolyTerminal.Take(fields.toList())
    }

    fun count() {
        check(terminal == null) { "Terminal already set" }
        terminal = PolyTerminal.Count
    }

    fun build(): PolyQuery {
        return PolyQuery(
            path = checkNotNull(path) {"No path set - use from()"},
            terminal = checkNotNull(terminal) { "No terminal set — use take() or count()" }
        )
    }
}

fun query(block: QueryBuilder.() -> Unit): PolyQuery {
    return QueryBuilder().apply(block).build()
}