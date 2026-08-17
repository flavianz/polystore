package query

data class GetQuery(
    val path: QueryPath,
    val limit: Int? = null
) {
    override fun toString(): String {
        return "${path}, limit: $limit"
    }
}