package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.connection.ConnectionManager
import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.CollectionPath
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
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
        initMongo(mongo)
    }

    DatabaseManager.initCollections(
        listOf(
            CollectionModel(
                "hospitals",
                mapOf(
                    "name" to DataType.STRING,
                    "patientCount" to DataType.INT,
                    "address" to DataType.STRING
                ),
                mutableListOf("departments")
            ),
            CollectionModel(
                "departments",
                mapOf(
                    "type" to DataType.STRING,
                    "capacity" to DataType.INT,
                    "building" to DataType.STRING
                ),
                mutableListOf("doctors")
            ),
            CollectionModel(
                "doctors",
                mapOf(
                    "first" to DataType.STRING,
                    "age" to DataType.INT,
                    "last" to DataType.STRING
                ),
                mutableListOf("patients")
            ),
            CollectionModel(
                "patients",
                mapOf(
                    "first" to DataType.STRING,
                    "age" to DataType.INT,
                    "last" to DataType.STRING
                )
            ),
            CollectionModel(
                "buildings",
                mapOf(
                    "address" to DataType.STRING,
                    "built_in" to DataType.INT,
                    "name" to DataType.STRING
                ),
                mutableListOf("rooms")
            ),
            CollectionModel(
                "rooms",
                mapOf(
                    "tag" to DataType.STRING,
                    "number" to DataType.INT,
                    "nurse" to DataType.STRING
                )
            )
            /*CollectionRef("users") to CollectionModel(
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
            )*/
        )
    )

    /*DatabaseManager.initConnections(
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
    )*/

    /*DatabaseManager.insertConnection(
        "posted",
        CollectionRef("posts"), UUID.fromString("a74be899-a5f1-4664-aeec-4b9730f49864"),
        CollectionRef("users"), UUID.fromString("fe40ffea-6cdf-408d-8fa9-6df6f78f2bee"),
        mapOf(
            "connection_data" to PolyValue.of("Hello World")
        )
    )*/

    manager.disconnectAll()
}

fun demo() {
    DatabaseManager.createCollection(
        CreateCollectionInstruction(
            CollectionModel(
                "schools", mapOf(
                    "name" to DataType.STRING,
                    "address" to DataType.STRING,
                    "student_count" to DataType.INT
                )
            )
        )
    )

    val gymOberwilUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("schools"), mapOf(
                "name" to PolyValue.of("Gymnasium Oberwil"),
                "address" to PolyValue.of("Allschwilerstrasse 100"),
                "student_count" to PolyValue.of(1000)
            )
        )
    )
    val gymMuttenzUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("schools"), mapOf(
                "name" to PolyValue.of("Gymnasium Muttenz"),
                "address" to PolyValue.of("Muttenzerstrasse 30"),
                "student_count" to PolyValue.of(800)
            )
        )
    )

    println(DatabaseManager.query(QueryParser("from schools s take s.name, s.student_count").parse()))

    DatabaseManager.createCollection(
        CreateCollectionInstruction(
            CollectionModel(
                "students", mapOf(
                    "first" to DataType.STRING,
                    "last" to DataType.STRING,
                    "age" to DataType.INT
                )
            ), "schools"
        )
    )


    val peterUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("schools", gymOberwilUUID.toString(), "students"), mapOf(
                "first" to PolyValue.of("Peter"),
                "last" to PolyValue.of("Müller"),
                "age" to PolyValue.of(17)
            )
        )
    )
    val hansUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("schools", gymOberwilUUID.toString(), "students"), mapOf(
                "first" to PolyValue.of("Hans"),
                "last" to PolyValue.of("Meier"),
                "age" to PolyValue.of(18)
            )
        )
    )
    val danielUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("schools", gymMuttenzUUID.toString(), "students"), mapOf(
                "first" to PolyValue.of("Daniel"),
                "last" to PolyValue.of("Hofer"),
                "age" to PolyValue.of(20)
            )
        )
    )

    println(DatabaseManager.query(QueryParser("from (schools s where student_count > 900).(students st) take s.name, st.last").parse()))
    println(DatabaseManager.query(QueryParser("from (schools s where student_count > 900).(students st where age > 17) take s.name, st.last, st.age").parse()))

    DatabaseManager.createCollection(
        CreateCollectionInstruction(
            CollectionModel(
                "courses", mapOf(
                    "subject" to DataType.STRING,
                    "teacher" to DataType.STRING,
                    "difficulty" to DataType.INT
                )
            )
        )
    )

    val mathUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("courses"), mapOf(
                "subject" to PolyValue.of("Math"),
                "teacher" to PolyValue.of("Wentzlaff"),
                "difficulty" to PolyValue.of(9)
            )
        )
    )
    val englishUUID = DatabaseManager.insertDocument(
        InsertObjectInstruction(
            CollectionPath("courses"), mapOf(
                "subject" to PolyValue.of("English"),
                "teacher" to PolyValue.of("Eberhardt"),
                "difficulty" to PolyValue.of(5)
            )
        )
    )

    DatabaseManager.createConnection(
        ConnectionModel(
            "studies",
            "students",
            "courses",
            mapOf(
                "grade" to DataType.INT,
                "year" to DataType.INT
            )
        )
    )

    DatabaseManager.insertConnection(
        "studies",
        "students", danielUUID,
        "courses", mathUUID,
        mapOf(
            "grade" to PolyValue.of(5),
            "year" to PolyValue.of(2025)
        )
    )
    DatabaseManager.insertConnection(
        "studies",
        "students", peterUUID,
        "courses", englishUUID,
        mapOf(
            "grade" to PolyValue.of(4),
            "year" to PolyValue.of(2023)
        )
    )
    DatabaseManager.insertConnection(
        "studies",
        "students", hansUUID,
        "courses", mathUUID,
        mapOf(
            "grade" to PolyValue.of(6),
            "year" to PolyValue.of(2026)
        )
    )

    println(DatabaseManager.query(QueryParser("from (schools sc).(students st)-(studies stu)-(courses c) take sc.name, st.last, c.subject, stu.year").parse()))
}