package ch.flavianz.driver

import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.Neo4jConnection
import ch.flavianz.data.PolyData
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.sql.Connection

object DriverManager {
    var postgresDriver: PostgresDriver? = null
    var mongoDriver: MongoDriver? = null
    var neo4jDriver: Neo4jDriver? = null

    fun execute(a: DatabaseDriver.() -> Unit) {
        postgresDriver?.a()
        mongoDriver?.a()
        neo4jDriver?.a()
    }

    fun initPostgres(jdbcConnection: Connection): DriverManager {
        val driver = PostgresDriver(jdbcConnection)
        driver.init()
        this.postgresDriver = driver
        return this
    }

    fun initMongo(mongoConnection: MongoConnection): DriverManager {
        val driver = MongoDriver(mongoConnection.mongoDatabase)
        driver.init()
        this.mongoDriver = driver
        return this
    }

    fun initNeo4j(neo4jConnection: Neo4jConnection): DriverManager {
        val driver = Neo4jDriver(neo4jConnection)
        driver.init()
        this.neo4jDriver = driver
        return this
    }

    fun take(query: PolyQuery, terminal: PolyTerminal.Take): List<PolyData> {
        return (this.postgresDriver ?: throw NotImplementedError("postgres not connected")).take(query.path, terminal)
    }

    fun count(query: PolyQuery, terminal: PolyTerminal.Count): PolyResult.Count {
        return (this.mongoDriver ?: throw NotImplementedError("postgres not connected")).count(query.path, terminal)
    }

    fun parseDatabaseSchema(): DatabaseSchema {
        val schemas = mutableListOf<DatabaseSchema>()
        mongoDriver?.getDatabaseSchema().let {
            if (it != null) {
                schemas.add(it)
            }
        }
        postgresDriver?.getDatabaseSchema().let {
            if (it != null) {
                schemas.add(it)
            }
        }
        neo4jDriver?.getDatabaseSchema().let {
            if (it != null) {
                schemas.add(it)
            }
        }
        if (schemas.isEmpty()) {
            throw IllegalStateException("no source connected to parse schema from")
        }
        if (schemas.distinct().size > 1) {
            throw IllegalStateException("not all connected sources have the same schema")
        }
        return schemas.first()
    }
}

fun addChildCollections(collections: List<CollectionModel>) {
    for (collection in collections) {
        if (collection.parentCollection != null) {
            val parentCollection = collections.firstOrNull { it.name == collection.parentCollection }
            checkNotNull(parentCollection) { "Parent collection ${collection.parentCollection} not found" }
            parentCollection.childCollections.add(collection.name)
        }
    }
}