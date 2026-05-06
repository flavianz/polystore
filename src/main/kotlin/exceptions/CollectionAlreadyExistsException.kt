package ch.flavianz.exceptions

data class CollectionAlreadyExistsException(val name: String) : Exception()