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
import ch.flavianz.instructions.InstructionHandler
import ch.flavianz.instructions.QueryInstruction
import ch.flavianz.query.QueryParser

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

    val handler = InstructionHandler()

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

    val parser = QueryParser("""
    from animals.(meals m where smell < 11) count
""")

    handler.handle(QueryInstruction(parser.parse()))

    manager.disconnectAll()
}