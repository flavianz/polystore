package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.connection.ConnectionManager
import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.data.CollectionRef
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.ObjectSchema
import ch.flavianz.query.CreateQuery
import ch.flavianz.query.QueryHandler

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
    val mongo = manager.get<MongoConnection>("MongoDB[polystore]")

    DriverManager.initialize {
        initPostgres(pg.jdbcConnection)
    }

    val handler = QueryHandler()

    DatabaseManager.initRootCollections(mutableMapOf(Pair("animalss", CollectionModel("animalss",
        ObjectSchema(mapOf(Pair("name", DataType.String), Pair("age", DataType.Int))),
        mutableMapOf(Pair("toys", CollectionModel("toys",
            ObjectSchema(mapOf(Pair("kind", DataType.String), Pair("size", DataType.Int))))), Pair("meals", CollectionModel("meals",
            ObjectSchema(mapOf(Pair("type", DataType.String), Pair("smell", DataType.Int))), mutableMapOf(Pair("toys", CollectionModel("toys",
                ObjectSchema(mapOf(Pair("kind", DataType.String), Pair("size", DataType.Int))))), Pair("meals", CollectionModel("meals",
                ObjectSchema(mapOf(Pair("type", DataType.String), Pair("smell", DataType.Int)))))))))))))

    handler.query(CreateQuery(CollectionModel("meals",
        ObjectSchema(mapOf(Pair("type", DataType.String), Pair("smell", DataType.Int)))), CollectionRef("animalss.meals")))

    manager.disconnectAll()
}