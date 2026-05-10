package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.connection.ConnectionManager
import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.data.CollectionPathRef
import ch.flavianz.data.CollectionRef
import ch.flavianz.data.DataObject
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.CollectionConnection
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.ObjectSchema
import ch.flavianz.query.InsertObjectQuery
import ch.flavianz.query.QueryHandler
import ch.flavianz.query.UpdateObjectQuery
import java.util.UUID

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

    DatabaseManager.initRootCollections(mutableMapOf(Pair("friends", CollectionModel("friends", ObjectSchema(mapOf(Pair("language", DataType.STRING), Pair("height", DataType.INT))))), Pair("animals", CollectionModel("animals",
        ObjectSchema(mapOf(Pair("name", DataType.STRING), Pair("age", DataType.INT))),
        mutableMapOf(Pair("toys", CollectionModel("toys",
            ObjectSchema(mapOf(Pair("kind", DataType.STRING), Pair("size", DataType.INT))))), Pair("meals", CollectionModel("meals",
            ObjectSchema(mapOf(Pair("type", DataType.STRING), Pair("smell", DataType.INT))), mutableMapOf(Pair("toys", CollectionModel("toys",
                ObjectSchema(mapOf(Pair("kind", DataType.STRING), Pair("size", DataType.INT))))), Pair("meals", CollectionModel("meals",
                ObjectSchema(mapOf(Pair("type", DataType.STRING), Pair("smell", DataType.INT)))))))))))))


    DatabaseManager.initConnections(mutableMapOf(Pair("toy_friends", CollectionConnection("toy_friends", CollectionRef("animals.toys"),
        CollectionRef("friends"), ObjectSchema(mapOf(Pair("since", DataType.INT), Pair("strength", DataType.INT)))
    ))))

    handler.query(UpdateObjectQuery(CollectionPathRef("animals").doc(UUID.fromString("131ea425-4e7a-4e94-95a9-0cf8d0c40af3")).sub("meals").doc("278b87e7-1b75-4552-acfb-a2ef7a8357ff"), DataObject(mapOf(Pair("type", "Tomato Spaghetti"), Pair("smell", 10)))))

    manager.disconnectAll()
}