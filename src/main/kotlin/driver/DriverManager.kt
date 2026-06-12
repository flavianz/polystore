package ch.flavianz.driver

import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.Neo4jConnection
import ch.flavianz.data.PolyData
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.sql.Connection

class DriverManager private constructor() {
    var postgresDriver: PostgresDriver? = null
    var mongoDriver: MongoDriver? = null
    var neo4jDriver: Neo4jDriver? = null

    fun execute(a: DatabaseDriver.() -> Unit) {
        postgresDriver?.a()
        mongoDriver?.a()
        neo4jDriver?.a()
    }

    fun initPostgres(jdbcConnection: Connection): DriverManager {
        this.postgresDriver = PostgresDriver(jdbcConnection)
        return this
    }

    fun initMongo(mongoConnection: MongoConnection): DriverManager {
        this.mongoDriver = MongoDriver(mongoConnection.mongoDatabase)
        return this
    }

    fun initNeo4j(neo4jConnection: Neo4jConnection): DriverManager {
        this.neo4jDriver = Neo4jDriver(neo4jConnection)
        return this
    }

    fun take(query: PolyQuery, terminal: PolyTerminal.Take): List<PolyData> {
        return (this.mongoDriver ?: throw NotImplementedError("postgres not connected")).take(query.path, terminal)
    }

    fun count(query: PolyQuery, terminal: PolyTerminal.Count): PolyResult.Count {
        return (this.mongoDriver ?: throw NotImplementedError("postgres not connected")).count(query.path, terminal)
    }

    companion object {
        @Volatile
        private var instance: DriverManager? = null

        fun initialize(block: DriverManager.() -> Unit): DriverManager {
            check(instance == null) { "DriverManager is already initialized" }
            return synchronized(this) {
                check(instance == null) { "DriverManager is already initialized" }
                DriverManager().apply(block).also { instance = it }
            }
        }

        fun getInstance(): DriverManager =
            checkNotNull(instance) { "DriverManager is not initialized. Call initialize() first." }
    }
}