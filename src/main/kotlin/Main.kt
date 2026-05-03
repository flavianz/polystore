package ch.flavianz

import ch.flavianz.core.connection.ConnectionManager
import ch.flavianz.core.connection.MongoConnection
import ch.flavianz.core.connection.PostgresConnection

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main() {
    val manager = ConnectionManager()

    // Register both connections
    manager.register(
        PostgresConnection(
            host = "localhost",
            port = 5432,
            database = "polystore",
            username = "postgres",
            password = "password"
        )
    )
    manager.register(
        MongoConnection(
            host = "localhost",
            port = 27017,
            database = "polystore"
        )
    )

    manager.connectAll()

    val health = manager.healthCheck()
    health.forEach { (name, alive) ->
        println("$name → ${if (alive) "OK" else "UNREACHABLE"}")
    }

    val pg = manager.get<PostgresConnection>("PostgreSQL[polystore]")
    val resultSet = pg.jdbcConnection.createStatement().executeQuery("SELECT version()")
    if (resultSet.next()) println("Postgres version: ${resultSet.getString(1)}")

    val mongo = manager.get<MongoConnection>("MongoDB[polystore]")
    println("MongoDB collections: ${mongo.mongoDatabase.listCollectionNames().toList()}")

    manager.disconnectAll()
}