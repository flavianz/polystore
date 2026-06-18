package ch.flavianz.connection

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session

/**
 * Manages a connection to a Neo4j database via the official Kotlin driver.
 */
class Neo4jConnection(
    private val host: String = "localhost",
    private val port: Int = 7687,
    private val database: String = "neo4j",
    private val username: String? = null,
    private val password: String? = null,
) : DatabaseConnection {

    override val name: String = "Neo4j[$database]"

    private var driver: Driver? = null

    val neo4jSession: Session
        get() = driver?.session(org.neo4j.driver.SessionConfig.forDatabase(database))
            ?: error("$name is not connected. Call connect() first.")

    override val isConnected: Boolean
        get() = driver != null

    override fun connect() {
        if (isConnected) return
        val uri = "bolt://$host:$port"
        driver = if (username != null && password != null) {
            GraphDatabase.driver(uri, AuthTokens.basic(username, password))
        } else {
            GraphDatabase.driver(uri, AuthTokens.none())
        }
        println("[$name] Connected.")
    }

    override fun disconnect() {
        driver?.close()
        driver = null
        println("[$name] Disconnected.")
    }

    override fun ping(): Boolean {
        return try {
            neo4jSession.use { it.run("RETURN 1") }
            true
        } catch (_: Exception) {
            false
        }
    }
}