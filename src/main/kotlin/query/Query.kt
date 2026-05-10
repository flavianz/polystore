package ch.flavianz.query

data class Query(val selectors: List<Selector>, val collector: Collector)
