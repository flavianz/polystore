package ch.flavianz.connection

class ConnectionManager {

    private val connections = mutableMapOf<String, DatabaseConnection>()

    /**
     * Register a connection under its name.
     * Throws if a connection with the same name already exists.
     */
    fun register(connection: DatabaseConnection) {
        require(!connections.containsKey(connection.name)) {
            "A connection named '${connection.name}' is already registered."
        }
        connections[connection.name] = connection
        println("[ConnectionManager] Registered '${connection.name}'.")
    }

    /**
     * Retrieve a connection by name, cast to the expected type.
     * Throws if not found or wrong type.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : DatabaseConnection> get(name: String): T {
        return (connections[name] as? T)
            ?: error("No connection named '$name' found.")
    }

    /** Connect all registered connections. */
    fun connectAll() {
        connections.values.forEach { it.connect() }
    }

    /** Disconnect all registered connections. */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
    }

    /** Ping all connections and return a status map. */
    fun healthCheck(): Map<String, Boolean> {
        return connections.mapValues { (_, conn) -> conn.ping() }
    }

    /** List all registered connection names and their status. */
    fun status(): Map<String, Boolean> {
        return connections.mapValues { (_, conn) -> conn.isConnected }
    }
}