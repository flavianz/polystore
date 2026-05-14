package ch.flavianz.query

class QueryBuilder {
    private val path = mutableListOf<PathNode>()
    private var terminal: PolyTerminal? = null

    fun from(vararg steps: PathNode) {
        path.addAll(steps)
    }

    fun take(vararg fields: FieldRef) {
        check(terminal == null) { "Terminal already set" }
        terminal = PolyTerminal.Take(fields.toList())
    }

    fun count(alias: String? = null) {
        check(terminal == null) { "Terminal already set" }
        terminal = PolyTerminal.Count(alias)
    }

    fun build(): PolyQuery {
        return PolyQuery(
            path = path.toList(),
            terminal = checkNotNull(terminal) { "No terminal set — use take() or count()" }
        )
    }
}

fun query(block: QueryBuilder.() -> Unit): PolyQuery {
    return QueryBuilder().apply(block).build()
}