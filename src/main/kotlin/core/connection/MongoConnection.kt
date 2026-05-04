package ch.flavianz.core.connection

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase

/**
 * Manages a connection to a MongoDB database via the official Kotlin driver.
 */
class MongoConnection(
    private val host: String = "localhost",
    private val port: Int = 27017,
    private val database: String,
    private val username: String? = null,
    private val password: String? = null,
) : DatabaseConnection {

    override val name: String = "MongoDB[$database]"

    private var client: MongoClient? = null

    // Exposes the raw MongoDatabase to document adapters that need it
    val mongoDatabase: MongoDatabase
        get() = client?.getDatabase(database)
            ?: error("$name is not connected. Call connect() first.")

    override val isConnected: Boolean
        get() = client != null

    override fun connect() {
        if (isConnected) return
        val uri = buildConnectionString()
        client = MongoClients.create(uri)
        println("[$name] Connected.")
    }

    override fun disconnect() {
        client?.close()
        client = null
        println("[$name] Disconnected.")
    }

    override fun ping(): Boolean {
        return try {
            mongoDatabase.runCommand(org.bson.Document("ping", 1))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildConnectionString(): String {
        return if (username != null && password != null) {
            "mongodb://$username:$password@$host:$port/$database"
        } else {
            "mongodb://$host:$port/$database"
        }
    }
}