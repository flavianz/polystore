package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.connection.ConnectionManager
import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.data.PolyDocument
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.CollectionPath
import ch.flavianz.model.DataType
import ch.flavianz.model.ObjectSchema
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DocumentPath
import ch.flavianz.query.QueryParser
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
        initMongo(mongo)
    }

    DatabaseManager.initCollections(
        mutableMapOf(
            /*CollectionRef("hospitals") to CollectionModel(
                "hospitals", ObjectSchema(
                    mapOf(
                        "name" to DataType.STRING,
                        "patientCount" to DataType.INT,
                        "address" to DataType.STRING
                    )
                )
            ),
            CollectionRef("hospitals", "departments") to CollectionModel(
                "departments", ObjectSchema(
                    mapOf(
                        "type" to DataType.STRING,
                        "capacity" to DataType.INT,
                        "building" to DataType.STRING
                    )
                )
            ),
            CollectionRef("hospitals", "departments", "doctors") to CollectionModel(
                "doctors", ObjectSchema(
                    mapOf(
                        "first" to DataType.STRING,
                        "age" to DataType.INT,
                        "last" to DataType.STRING
                    )
                )
            ),
            CollectionRef("hospitals", "departments", "doctors", "patients") to CollectionModel(
                "patients", ObjectSchema(
                    mapOf(
                        "first" to DataType.STRING,
                        "age" to DataType.INT,
                        "last" to DataType.STRING
                    )
                )
            ),
            CollectionRef("buildings") to CollectionModel(
                "buildings", ObjectSchema(
                    mapOf(
                        "address" to DataType.STRING,
                        "built_in" to DataType.INT,
                        "name" to DataType.STRING
                    )
                )
            ),
            CollectionRef("buildings", "rooms") to CollectionModel(
                "rooms", ObjectSchema(
                    mapOf(
                        "tag" to DataType.STRING,
                        "number" to DataType.INT,
                        "nurse" to DataType.STRING
                    )
                )
            ),*/
            CollectionRef("users") to CollectionModel(
                "users", ObjectSchema(
                    mapOf(
                        "name" to DataType.STRING,
                        "age" to DataType.INT,
                    )
                )
            ),
            CollectionRef("posts") to CollectionModel(
                "posts", ObjectSchema(
                    mapOf("content" to DataType.STRING, "date" to DataType.INT)
                )
            )
        )
    )

    DatabaseManager.initConnections(
        mutableMapOf(
            /*"treated_in" to ConnectionModel(
                "treated_in",
                CollectionRef("hospitals", "departments", "doctors", "patients"),
                CollectionRef("buildings", "rooms"),
                ObjectSchema(mapOf("since" to DataType.INT, "price" to DataType.INT))
            )*/
            "posted" to ConnectionModel(
                "posted",
                CollectionRef("users"),
                CollectionRef("posts"),
                ObjectSchema(
                    mapOf(
                        "connection_data" to DataType.STRING
                    )
                )
            )
        )
    )

    DatabaseManager.insertConnection(
        "posted",
        CollectionRef("posts"), UUID.fromString("a74be899-a5f1-4664-aeec-4b9730f49864"),
        CollectionRef("users"), UUID.fromString("fe40ffea-6cdf-408d-8fa9-6df6f78f2bee"),
        mapOf(
            "connection_data" to PolyValue.of("Hello World")
        )
    )

    manager.disconnectAll()
}