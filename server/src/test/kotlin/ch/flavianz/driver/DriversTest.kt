package ch.flavianz.driver

import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.Neo4jConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import com.mongodb.client.model.Filters
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DriversTest {
    abstract fun initDriver()
    abstract fun detachDriver()
    abstract fun cleanUpDatabase()

    @BeforeEach
    fun beforeTest() {
        cleanUpDatabase()
    }

    @AfterEach
    fun afterEach() {
        DatabaseManager.initCollections(emptyList())
        DatabaseManager.initConnections(emptyList())
        cleanUpDatabase()
    }

    @BeforeAll
    fun beforeAll() {
        initDriver()
    }

    @AfterAll
    fun afterAll() {
        detachDriver()
    }

    @Test
    fun `create and drop a collection`() {
        DatabaseManager.createCollection("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
         val expected = setOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), null))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listCollections().toSet())
        assertEquals(expected, schema.collections)

        DatabaseManager.dropCollection("test")
        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptySet(), schema2.collections)
        assertEquals(emptySet(), DatabaseManager.listCollections().toSet())
    }

    @Test
    fun `create and drop a collection with subcollection`() {
        DatabaseManager.createCollection("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createCollection("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), "test")
        val expected = setOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf("test_child"), null), CollectionModel("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), "test"))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listCollections().toSet())
        assertEquals(expected, schema.collections)

        DatabaseManager.dropCollection("test", true)
        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptyList(), schema2.collections.toList())
        assertEquals(emptyList(), DatabaseManager.listCollections())
    }
    @Test
    fun `create and drop a collection with subcollection and 2nd level subcollection`() {
        DatabaseManager.createCollection("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createCollection("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), "test")
        DatabaseManager.createCollection("test_child2", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), "test_child")
        val expected = setOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf("test_child"), null), CollectionModel("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf("test_child2"), "test"), CollectionModel("test_child2", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), "test_child"))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listCollections().toSet())
        assertEquals(expected, schema.collections)

        DatabaseManager.dropCollection("test", true)
        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptySet(), schema2.collections)
        assertEquals(emptySet(), DatabaseManager.listCollections().toSet())
    }

    @Test
    fun `create and drop a connection`() {
        DatabaseManager.createCollection("a", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createCollection("b", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createConnection(ConnectionModel("test", "a", "b", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        )))
        val expected = setOf(ConnectionModel("test", "a", "b", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ),))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listConnections().toSet())
        assertEquals(expected, schema.connections)

        DatabaseManager.dropConnection("test")
        DatabaseManager.dropCollection("a")
        DatabaseManager.dropCollection("b")

        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptySet(), schema2.connections)
        assertEquals(emptySet(), DatabaseManager.listConnections().toSet())
    }

    @Test
    fun `create and drop a connection with subcollection`() {
        DatabaseManager.createCollection("a", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createCollection("b", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createCollection("c", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), "b")
        DatabaseManager.createConnection(ConnectionModel("test", "a", "c", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        )))
        val expected = setOf(ConnectionModel("test", "a", "c", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ),))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listConnections().toSet())
        assertEquals(expected, schema.connections)

        DatabaseManager.dropConnection("test")
        DatabaseManager.dropCollection("a")
        DatabaseManager.dropCollection("b", true)

        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptySet(), schema2.connections)
        assertEquals(emptySet(), DatabaseManager.listConnections().toSet())
    }
    @Test
    fun `create and drop a connection with two subcollections`() {
        DatabaseManager.createCollection("a", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        DatabaseManager.createCollection("b", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), "a")
        DatabaseManager.createCollection("c", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ),)
        DatabaseManager.createCollection("d", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), "c")
        DatabaseManager.createConnection(ConnectionModel("test", "b", "d", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        )))
        val expected = setOf(ConnectionModel("test", "b", "d", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ),))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listConnections().toSet())
        assertEquals(expected, schema.connections)

        DatabaseManager.dropConnection("test")
        DatabaseManager.dropCollection("a", true)
        DatabaseManager.dropCollection("c", true)

        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptySet(), schema2.connections)
        assertEquals(emptySet(), DatabaseManager.listConnections().toSet())
    }
}

class PostgresDriverTest : DriversTest() {
    private lateinit var conn: PostgresConnection;
    override fun initDriver() {
        conn = PostgresConnection(
            host = "localhost",
            port = 5432,
            database = "polystore_test",
            username = "postgres",
            password = "password"
        )
        conn.connect()
        DriverManager.initPostgres(
            conn.jdbcConnection)
    }

    override fun detachDriver() {
        DriverManager.detachPostgres()
    }

    override fun cleanUpDatabase() {
        conn.jdbcConnection.createStatement().execute("DROP TABLE IF EXISTS ps_col_test;")
        conn.jdbcConnection.createStatement().execute("DELETE FROM ps_config_connections;")
        conn.jdbcConnection.createStatement().execute("DELETE FROM ps_config_collections;")
    }
}

class MongoDriverTest : DriversTest() {
    private lateinit var conn: MongoConnection;

    override fun initDriver() {
        conn = MongoConnection(
            host = "localhost",
            port = 27017,
            database = "polystore_test"
        )
        conn.connect()
        DriverManager.initMongo(
            conn
        )
    }

    override fun detachDriver() {
        DriverManager.detachMongo()
    }

    override fun cleanUpDatabase() {
        conn.mongoDatabase.getCollection("test").drop()
        conn.mongoDatabase.getCollection("ps_config_collections").deleteMany(Filters.empty())
        conn.mongoDatabase.getCollection("ps_config_connections").deleteMany(Filters.empty())
    }
}

class Neo4jDriverTest : DriversTest() {
    private lateinit var conn: Neo4jConnection;

    override fun initDriver() {
        conn = Neo4jConnection(
            username = "neo4j",
            password = "password"
        )
        conn.connect()
        DriverManager.initNeo4j(conn)
    }

    override fun detachDriver() {
        DriverManager.detachNeo4j()
    }

    override fun cleanUpDatabase() {
        conn.neo4jSession.run("MATCH (n: test) DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_collection) WHERE n.name = 'test' DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_collection) WHERE n.name = 'test_child' DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_collection) WHERE n.name = 'test_child2' DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_connection) WHERE n.name = 'a' DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_connection) WHERE n.name = 'b' DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_connection) WHERE n.name = 'c' DETACH DELETE n").consume()
        conn.neo4jSession.run("MATCH (n:ps_config_connection) WHERE n.name = 'c' DETACH DELETE n").consume()
    }
}