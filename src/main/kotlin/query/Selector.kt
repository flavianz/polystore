package ch.flavianz.query

data class Selector(val collectionName: String, val filters: List<Filter>, val propertyName: String? = null)
