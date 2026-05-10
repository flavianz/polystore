package ch.flavianz.query

sealed class Collector {
    data class CollectCollector(val propertyName: String) : Collector()
    data class TakeCollector(val properties: Map<String, List<String>>) : Collector()
}