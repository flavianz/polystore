package ch.flavianz.query

data class GetQuery(
    val path: QueryPath,
    val limit: Int? = null
)