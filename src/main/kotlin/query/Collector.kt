package ch.flavianz.query

sealed class Collector {
    data class CollectCollector(val propertyName: String?)
    data class TakeCollector(val properties: Map<String, List<String>>)
}