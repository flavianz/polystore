package ch.flavianz.data

data class PolyDocument(val fields: Map<String, PolyValue>)

typealias PolyData = Map<String, PolyValue>