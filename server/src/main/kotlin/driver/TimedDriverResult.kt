package driver

import query.PolyDriverQueryDuration

data class TimedDriverResult<T>(
    val data: T,
    val duration: PolyDriverQueryDuration,
    val executedQueries: List<String>
)