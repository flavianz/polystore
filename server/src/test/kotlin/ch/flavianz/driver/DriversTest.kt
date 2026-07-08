package ch.flavianz.driver

import ch.flavianz.connection.MongoConnection
import ch.flavianz.connection.Neo4jConnection
import ch.flavianz.connection.PostgresConnection
import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
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
        val expected = listOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), null))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listCollections())
        assertEquals(expected, schema.collections.toList())

        DatabaseManager.dropCollection("test")
        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptyList(), schema2.collections.toList())
        assertEquals(emptyList(), DatabaseManager.listCollections())
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
        ))
        val expected = listOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf("test_child"), null), CollectionModel("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), "test"))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listCollections())
        assertEquals(expected, schema.collections.toList())

        DatabaseManager.dropCollection("test")
        DatabaseManager.dropCollection("test_child")
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
        ))
        DatabaseManager.createCollection("test_child2", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        val expected = listOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf("test_child"), null), CollectionModel("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf("test_child2"), "test"), CollectionModel("test_child", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), "test_child"))

        val schema = DriverManager.parseDatabaseSchema()
        assertEquals(expected, DatabaseManager.listCollections())
        assertEquals(expected, schema.collections.toList())

        DatabaseManager.dropCollection("test")
        DatabaseManager.dropCollection("test_child")
        DatabaseManager.dropCollection("test_child2")
        val schema2 = DriverManager.parseDatabaseSchema()
        assertEquals(emptyList(), schema2.collections.toList())
        assertEquals(emptyList(), DatabaseManager.listCollections())
    }



    @Test
    fun `create collection with subcollection`() {
        DatabaseManager.createCollection("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ))
        assertEquals(DatabaseManager.listCollections(), listOf(CollectionModel("test", mapOf(
            "name" to DataType.STRING,
            "age" to DataType.INT,
        ), mutableListOf(), null)))
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
        conn.jdbcConnection.createStatement().execute("DELETE FROM ps_config_collections;")
        conn.jdbcConnection.createStatement().execute("DELETE FROM ps_config_connections;")
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
        conn.neo4jSession.run("MATCH (n:ps_config_collections) WHERE n.name = 'test' DETACH DELETE n").consume()
    }
}