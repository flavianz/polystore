package ch.flavianz.connection

interface DatabaseConnection {
    val name: String
    val isConnected: Boolean

    fun connect()
    fun disconnect()
    fun ping(): Boolean
}