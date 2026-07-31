package ch.flavianz.model

class QueryPath(private val segments: List<QuerySegment>) : ArrayList<QuerySegment>(segments) {
    init {
        require(segments.isNotEmpty()) { "query path cannot be empty" }
        require(segments.first() is QuerySegment.Collection) { "first segment of query path must be a collection" }
    }

    override fun toString(): String {
        return segments.joinToString(".")
    }
}