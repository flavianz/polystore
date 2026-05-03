package ch.flavianz.core.connection

import java.sql.Connection
import java.sql.DriverManager

/**
 * Manages a connection to a PostgreSQL database via JDBC.
 *
 * Dependencies (add to build.gradle.kts):
 *   implementation("org.postgresql:postgresql:42.7.3")
 */
class PostgresConnection(
    private val host: String = "localhost",
    private val port: Int = 5432,
    private val database: String,
    private val username: String,
    private val password: String,
) : DatabaseConnection {

    override val name: String = "PostgreSQL[$database]"

    private var _connection: Connection? = null

    // Exposes the raw JDBC connection to SQL adapters that need it
    val jdbcConnection: Connection
        get() = _connection ?: error("$name is not connected. Call connect() first.")

    override val isConnected: Boolean
        get() = _connection?.isClosed == false

    override fun connect() {
        if (isConnected) return
        val url = "jdbc:postgresql://$host:$port/$database"
        _connection = DriverManager.getConnection(url, username, password)
        println("[$name] Connected.")
    }

    override fun disconnect() {
        _connection?.close()
        _connection = null
        println("[$name] Disconnected.")
    }

    override fun ping(): Boolean {
        return try {
            _connection?.createStatement()?.execute("SELECT 1") == true
        } catch (_: Exception) {
            false
        }
    }
}