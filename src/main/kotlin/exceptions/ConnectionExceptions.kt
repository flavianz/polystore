package ch.flavianz.exceptions

data class ConnectionAlreadyExistsException(val name: String) : Exception() {
    override fun toString(): String {
        return "Connection already exists: \"$name\""
    }
}