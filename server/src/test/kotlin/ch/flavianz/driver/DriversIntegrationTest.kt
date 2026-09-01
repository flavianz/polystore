package ch.flavianz.driver

import connection.MongoConnection
import connection.Neo4jConnection
import connection.PostgresConnection
import core.DatabaseManager
import model.CollectionModel
import model.ConnectionModel
import model.DataType
import query.PolyResultData
import query.eq
import ch.flavianz.query.get
import com.mongodb.client.model.Filters
import driver.DriverManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
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
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        val expected = setOf(
            CollectionModel(
                "test", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ), mutableListOf(), null
            )
        )

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
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "test_child", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test"
        )
        val expected = setOf(
            CollectionModel(
                "test", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ), mutableListOf("test_child"), null
            ), CollectionModel(
                "test_child", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ), mutableListOf(), "test"
            )
        )

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
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "test_child", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test"
        )
        DatabaseManager.createCollection(
            "test_child2", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test_child"
        )
        val expected = setOf(
            CollectionModel(
                "test", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ), mutableListOf("test_child"), null
            ), CollectionModel(
                "test_child", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ), mutableListOf("test_child2"), "test"
            ), CollectionModel(
                "test_child2", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ), mutableListOf(), "test_child"
            )
        )

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
        DatabaseManager.createCollection(
            "a", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "b", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createConnection(
            ConnectionModel(
                "test", "a", "b", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                )
            )
        )
        val expected = setOf(
            ConnectionModel(
                "test", "a", "b",
                mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ),
            )
        )

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
        DatabaseManager.createCollection(
            "a", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "b", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "c", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "b"
        )
        DatabaseManager.createConnection(
            ConnectionModel(
                "test", "a", "c", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                )
            )
        )
        val expected = setOf(
            ConnectionModel(
                "test", "a", "c",
                mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ),
            )
        )

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
        DatabaseManager.createCollection(
            "a", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "b", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "a"
        )
        DatabaseManager.createCollection(
            "c",
            mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ),
        )
        DatabaseManager.createCollection(
            "d", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "c"
        )
        DatabaseManager.createConnection(
            ConnectionModel(
                "test", "b", "d", mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                )
            )
        )
        val expected = setOf(
            ConnectionModel(
                "test", "b", "d",
                mapOf(
                    "name" to DataType.STRING,
                    "age" to DataType.INT,
                ),
            )
        )

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

    @Test
    fun `insert doc and retrieve it`() {
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Tim",
                "age" to 18,
            )
        )
        val response = DatabaseManager.get(get {
            collection("test", only = listOf("name", "age"))
        })
        assertEquals(
            setOf(
                mapOf(
                    "test.name" to "Tim",
                    "test.age" to 18,
                )
            ), (response.resultData as PolyResultData.Documents).polyData.toSet()
        )
        DatabaseManager.dropCollection("test")
    }

    @Test
    fun `insert doc with dynamic data and retrieve it`() {
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        val randomId = UUID.randomUUID()
        val docId = DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Tim",
                "age" to 18,
                "someId" to randomId,
                "male" to true
            )
        )
        val response = DatabaseManager.get(get {
            collection("test")
        })
        assertEquals(
            setOf(
                mapOf(
                    "test._id" to docId,
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test.someId" to randomId,
                    "test.male" to true
                )
            ), (response.resultData as PolyResultData.Documents).polyData.toSet()
        )
        val responseOnly = DatabaseManager.get(get {
            collection("test", only = listOf("name", "age", "someId", "male"))
        })
        assertEquals(
            setOf(
                mapOf(
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test.someId" to randomId,
                    "test.male" to true
                )
            ), (responseOnly.resultData as PolyResultData.Documents).polyData.toSet()
        )
        DatabaseManager.dropCollection("test")
    }

    @Test
    fun `insert doc with dynamic data and filter on dynamic data`() {
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        val randomId = UUID.randomUUID()
        val docId = DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Tim",
                "age" to 18,
                "someId" to randomId,
                "male" to true
            )
        )
        DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Peter",
                "age" to 20,
                "someId" to UUID.randomUUID(),
                "male" to false
            )
        )
        val response = DatabaseManager.get(get {
            collection("test", "male" eq true)
        })
        println(response.executionEnvironment.executedQueries)
        assertEquals(
            setOf(
                mapOf(
                    "test._id" to docId,
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test.someId" to randomId,
                    "test.male" to true
                )
            ), (response.resultData as PolyResultData.Documents).polyData.toSet()
        )
        val responseOnly = DatabaseManager.get(get {
            collection("test", "someId" eq randomId, only = listOf("name", "age", "someId", "male"))
        })
        assertEquals(
            setOf(
                mapOf(
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test.someId" to randomId,
                    "test.male" to true
                )
            ), (responseOnly.resultData as PolyResultData.Documents).polyData.toSet()
        )
        DatabaseManager.dropCollection("test")
    }

    @Test
    fun `insert doc and subdoc and retrieve it`() {
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "test_child", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test"
        )
        val parentUuid = DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Tim",
                "age" to 18,
            )
        )
        val childUuid = DatabaseManager.insertDocument(
            "test_child", mapOf(
                "name" to "Bob",
                "age" to 20,
            ), parentUuid
        )
        val response = DatabaseManager.get(get {
            collection("test")
            collection("test_child")
        })
        assertEquals(
            setOf(
                mapOf(
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test._id" to parentUuid,
                    "test_child.name" to "Bob",
                    "test_child.age" to 20,
                    "test_child._id" to childUuid,
                )
            ), (response.resultData as PolyResultData.Documents).polyData.toSet()
        )
        DatabaseManager.dropCollection("test", true)
    }

    @Test
    fun `insert doc and subdoc and subsubdoc and retrieve it`() {
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "test_child", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test"
        )
        DatabaseManager.createCollection(
            "test_child2", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test_child"
        )
        val parentUuid = DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Tim",
                "age" to 18,
            )
        )
        val childUuid = DatabaseManager.insertDocument(
            "test_child", mapOf(
                "name" to "Bob",
                "age" to 20,
            ), parentUuid
        )
        val childUuid2 = DatabaseManager.insertDocument(
            "test_child2", mapOf(
                "name" to "Tom",
                "age" to 22,
            ), childUuid
        )
        val response = DatabaseManager.get(get {
            collection("test")
            collection("test_child")
            collection("test_child2")
        })
        assertEquals(
            setOf(
                mapOf(
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test._id" to parentUuid,
                    "test_child.name" to "Bob",
                    "test_child.age" to 20,
                    "test_child._id" to childUuid,
                    "test_child2.name" to "Tom",
                    "test_child2.age" to 22,
                    "test_child2._id" to childUuid2,
                )
            ), (response.resultData as PolyResultData.Documents).polyData.toSet()
        )
        DatabaseManager.dropCollection("test", true)
    }

    @Test
    fun `insert doc and subdoc and subsubdoc and subsubdoc and retrieve it`() {
        DatabaseManager.createCollection(
            "test", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            )
        )
        DatabaseManager.createCollection(
            "test_child", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test"
        )
        DatabaseManager.createCollection(
            "test_child2", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test_child"
        )
        DatabaseManager.createCollection(
            "test_child3", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
            ), "test_child2"
        )
        val parentUuid = DatabaseManager.insertDocument(
            "test", mapOf(
                "name" to "Tim",
                "age" to 18,
            )
        )
        val childUuid = DatabaseManager.insertDocument(
            "test_child", mapOf(
                "name" to "Bob",
                "age" to 20,
            ), parentUuid
        )
        val childUuid2 = DatabaseManager.insertDocument(
            "test_child2", mapOf(
                "name" to "Tom",
                "age" to 22,
            ), childUuid
        )
        val childUuid3 = DatabaseManager.insertDocument(
            "test_child3", mapOf(
                "name" to "Remo",
                "age" to 30,
            ), childUuid2
        )
        val response = DatabaseManager.get(get {
            collection("test")
            collection("test_child")
            collection("test_child2")
            collection("test_child3")
        })
        assertEquals(
            setOf(
                mapOf(
                    "test.name" to "Tim",
                    "test.age" to 18,
                    "test._id" to parentUuid,
                    "test_child.name" to "Bob",
                    "test_child.age" to 20,
                    "test_child._id" to childUuid,
                    "test_child2.name" to "Tom",
                    "test_child2.age" to 22,
                    "test_child2._id" to childUuid2,
                    "test_child3.name" to "Remo",
                    "test_child3.age" to 30,
                    "test_child3._id" to childUuid3,
                )
            ), (response.resultData as PolyResultData.Documents).polyData.toSet()
        )
        DatabaseManager.dropCollection("test", true)
    }


}

class PostgresDriverTest : DriversTest() {
    private lateinit var conn: PostgresConnection
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
            conn.jdbcConnection
        )
    }

    override fun detachDriver() {
        DriverManager.detachPostgres()
    }

    override fun cleanUpDatabase() {
        conn.jdbcConnection.createStatement().execute("DROP TABLE IF EXISTS ps_col_test;")
        conn.jdbcConnection.createStatement().execute("DELETE FROM ps_config_connections;")
        conn.jdbcConnection.createStatement().execute("DELETE FROM ps_config_collections;")
        DatabaseManager.initConnections(emptyList())
        DatabaseManager.initCollections(emptyList())
    }
}

class MongoDriverTest : DriversTest() {
    private lateinit var conn: MongoConnection

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
        DatabaseManager.initConnections(emptyList())
        DatabaseManager.initCollections(emptyList())
    }
}

class Neo4jDriverTest : DriversTest() {
    private lateinit var conn: Neo4jConnection

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
        DatabaseManager.initConnections(emptyList())
        DatabaseManager.initCollections(emptyList())
    }
}