package ch.flavianz.core.connection

interface DatabaseConnection {
    val name: String
    val isConnected: Boolean

    fun connect()
    fun disconnect()
    fun ping(): Boolean
}