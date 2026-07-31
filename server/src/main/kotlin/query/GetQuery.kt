package ch.flavianz.query

import ch.flavianz.model.QueryPath

data class GetQuery(
    val path: QueryPath,
    val limit: Int? = null
)