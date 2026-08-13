package ch.flavianz.stat

import ch.flavianz.query.GetQuery

data class BenchmarkQuery(
    val queryShape: String,
    val depth: Int,
    val filterCount: Int,
    val filterType: BenchFilterType,
    val query: GetQuery,
    val benchResultType: BenchResultType = BenchResultType.EntireDoc,
    val sizeLimit: Int? = null,
    val dynamicData: Boolean = false,
    val mongoMutliQuery: Boolean = false
)
