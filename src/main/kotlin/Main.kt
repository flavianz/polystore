package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.connection.ConnectionManager
import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.data.CollectionRef
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.CollectionConnection
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.ObjectSchema
import ch.flavianz.query.CreateCollectionQuery
import ch.flavianz.query.CreateConnectionQuery
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

    DatabaseManager.initRootCollections(mutableMapOf(Pair("friends", CollectionModel("friends", ObjectSchema(mapOf(Pair("language", DataType.String), Pair("height", DataType.Int))))), Pair("animals", CollectionModel("animals",
        ObjectSchema(mapOf(Pair("name", DataType.String), Pair("age", DataType.Int))),
        mutableMapOf(Pair("toys", CollectionModel("toys",
            ObjectSchema(mapOf(Pair("kind", DataType.String), Pair("size", DataType.Int))))), Pair("meals", CollectionModel("meals",
            ObjectSchema(mapOf(Pair("type", DataType.String), Pair("smell", DataType.Int))), mutableMapOf(Pair("toys", CollectionModel("toys",
                ObjectSchema(mapOf(Pair("kind", DataType.String), Pair("size", DataType.Int))))), Pair("meals", CollectionModel("meals",
                ObjectSchema(mapOf(Pair("type", DataType.String), Pair("smell", DataType.Int)))))))))))))


    handler.query(CreateConnectionQuery(CollectionConnection("toy_friends", CollectionRef("animals.toys"),
        CollectionRef("friends"), ObjectSchema(mapOf(Pair("since", DataType.Int), Pair("strength", DataType.Int)))
    )))

    manager.disconnectAll()
}