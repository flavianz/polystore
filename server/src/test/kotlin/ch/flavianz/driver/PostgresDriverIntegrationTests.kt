package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.*
import ch.flavianz.query.gt
import ch.flavianz.query.query
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class PostgresDriverIntegrationTests {

    private val host = System.getenv("TEST_DB_HOST") ?: "localhost"
    private val port = (System.getenv("TEST_DB_PORT") ?: "5432").toInt()
    private val database = System.getenv("TEST_DB_DATABASE") ?: "polystore_test"
    private val username = System.getenv("TEST_DB_USERNAME") ?: "postgres"
    private val password = System.getenv("TEST_DB_PASSWORD") ?: "password"

    private val userSchema = mapOf("name" to DataType.STRING, "age" to DataType.INT)
    private val orderSchema = mapOf("item" to DataType.STRING, "price" to DataType.INT)
    private val connectionSchema = mapOf("quantity" to DataType.INT)

    private val userModel = CollectionModel("test_users", userSchema, mutableListOf("test_orders"), null)
    private val orderModel = CollectionModel("test_orders", orderSchema, mutableListOf(), "test_users")

    private var connection: Connection? = null
    private var driver: PostgresDriver? = null

    private fun isDatabaseReachable(): Boolean {
        return try {
            DriverManager.getConnection("jdbc:postgresql://$host:$port/$database", username, password).use { true }
        } catch (_: Exception) {
            false
        }
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(isDatabaseReachable()) { "Live PostgreSQL database is not reachable at $host:$port. Skipping integration tests." }

        val conn = DriverManager.getConnection("jdbc:postgresql://$host:$port/$database", username, password)
        connection = conn
        driver = PostgresDriver(conn)
        driver?.init()

        // Reset and populate schema registry in DatabaseManager
        DatabaseManager.initCollections(
            listOf(
                userModel,
                orderModel
            )
        )
        DatabaseManager.initConnections(
            listOf(
                ConnectionModel(
                    "test_bought",
                    "test_users",
                    "test_orders",
                    connectionSchema
                )
            )
        )

        // Drop any leftover tables before running the test
        cleanupTables()
    }

    @AfterTest
    fun tearDown() {
        if (connection != null && !connection!!.isClosed) {
            cleanupTables()
            connection!!.close()
        }
    }

    private fun cleanupTables() {
        connection?.createStatement()?.use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS \"ps_con_test_users__test_bought__test_orders\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_test_orders\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_test_users\"")
            stmt.execute("DELETE FROM \"ps_config_connections\"")
            stmt.execute("DELETE FROM \"ps_config_collections\"")
        }
    }

    @Test
    fun testPostgresDriverLifecycleAndQueries() {
        val driver = this.driver ?: return

        // 1. Create collections
        driver.createCollection("test_users", userSchema)
        driver.createCollection(
            "test_orders", orderSchema,
            parentCollectionName = "test_users"
        )

        // 2. Create connection
        val connectionModel = ConnectionModel(
            name = "test_bought",
            collection1Name = "test_users",
            collection2Name = "test_orders",
            connectionDataSchema = connectionSchema
        )
        driver.createConnection(connectionModel)

        // 3. Insert Objects
        val userUuid = UUID.randomUUID()
        driver.insertDocument(userModel, userUuid, mapOf("name" to "Alice", "age" to 30))

        val orderUuid = UUID.randomUUID()
        driver.insertDocument(
            orderModel,
            orderUuid,
            mapOf("item" to "Laptop", "price" to 1200),
            userUuid
        )

        // Insert Connection record directly using SQL (as connection table is updated manually or via query in app design)
        connection!!.prepareStatement(
            "INSERT INTO \"ps_con_test_users__test_bought__test_orders\" " +
                    "(\"ps_cfk_test_users\", \"ps_cfk_test_orders\", \"quantity\") " +
                    "VALUES ('$userUuid', '$orderUuid', 5)"
        ).execute()

        driver.updateDocument(
            CollectionPath("test_users").doc(userUuid),
            (mapOf("age" to 31))
        )

        // 5. Test take (Wildcard)
        // path: test_users
        val takeResult = driver.get(query { collection("test_users") })

        assertEquals(1, takeResult.data.size)
        val userRow = takeResult.data[0]
        assertEquals(userUuid.toString(), userRow["test_users._id"]?.toString())
        assertEquals("Alice", userRow["test_users.name"])
        assertEquals(31, userRow["test_users.age"]) // updated age

        // 6. Test count
        //val countResult = driver.count(getQuery, PolyTerminal.Count)
        //assertEquals(1, countResult.count)

        val joinResult = driver.get(query {
            collection("test_users")
            connection("test_bought", "test_orders")
        })

        assertEquals(1, joinResult.data.size)
        val joinRow = joinResult.data[0]

        // Validate user fields in join
        assertEquals(userUuid.toString(), joinRow["test_users._id"]?.toString())
        assertEquals("Alice", joinRow["test_users.name"])
        assertEquals(31, joinRow["test_users.age"])

        // Validate connection fields in join
        val quantityKey = "test_bought.quantity"
        assertEquals(5, joinRow[quantityKey])

        // Validate order fields in join
        assertEquals(orderUuid.toString(), joinRow["test_orders._id"]?.toString())
        assertEquals("Laptop", joinRow["test_orders.item"])
        assertEquals(1200, joinRow["test_orders.price"])
    }

    @Test
    fun testPostgresDriverQueryWithConditions() {
        val driver = this.driver ?: return

        driver.createCollection("test_users", userSchema)

        val user1 = UUID.randomUUID()
        driver.insertDocument(
            userModel,
            user1, mapOf("name" to "Alice", "age" to 25)
        )

        val user2 = UUID.randomUUID()
        driver.insertDocument(
            userModel,
            user2, mapOf("name" to "Bob", "age" to 35)
        )

        val result = driver.get(query { collection("test_users", "age" gt 30) })

        assertEquals(1, result.data.size)
        assertEquals("Bob", result.data[0]["test_users.name"])
    }
}
