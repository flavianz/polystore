package ch.flavianz.driver

import kotlin.time.Duration

data class TimedQueryValue<T>(
    val value: T,
    val duration: Duration,
    val executedQuery: String
)