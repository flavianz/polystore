import benchmark.regression.BenchEnvironmentRegression
import benchmark.regression.RegressionBenchMeasurement
import core.DatabaseManager
import connection.ConnectionManager
import connection.MongoConnection
import connection.Neo4jConnection
import connection.PostgresConnection
import driver.DriverManager
import server.startServer

fun main() {
    val manager = ConnectionManager()

    // Register both connections
    manager.register(
        PostgresConnection(
            host = "127.0.0.1",
            port = 5432,
            database = "polystore",
            username = "postgres",
            password = "password"
        )
    )
    /*manager.register(
        MongoConnection(
            host = "127.0.0.1",
            port = 27017,
            database = "polystore"
        )
    )
    manager.register(
        Neo4jConnection(
            username = "neo4j",
            password = "password"
        )
    )*/

    manager.connectAll()

    val health = manager.healthCheck()
    health.forEach { (name, alive) ->
        println("$name → ${if (alive) "OK" else "UNREACHABLE"}")
    }

    val pg = manager.get<PostgresConnection>("PostgreSQL[polystore]")
    /*val mongo = manager.get<MongoConnection>("MongoDB[polystore]")
    val neo4j = manager.get<Neo4jConnection>("Neo4j[neo4j]")*/

    DriverManager.initPostgres(pg.jdbcConnection)
    /*DriverManager.initMongo(mongo)
    DriverManager.initNeo4j(neo4j)*/

    val databaseSchema = DriverManager.parseDatabaseSchema()

    DatabaseManager.initCollections(
        databaseSchema.collections.toList()
    )

    DatabaseManager.initConnections(
        databaseSchema.connections.toList()
    )
    BenchEnvironmentRegression().bench()

    //Benchmark.startBenchmark()

    // Don't disconnect - keep connections alive for the server
    Runtime.getRuntime().addShutdownHook(Thread {
        manager.disconnectAll()
    })

    startServer()
}
