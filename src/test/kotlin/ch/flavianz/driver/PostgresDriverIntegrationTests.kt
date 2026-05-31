package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyDocument
import ch.flavianz.data.PolyValue
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.*
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyTerminal
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

    private val userSchema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
    private val orderSchema = ObjectSchema(mapOf("item" to DataType.STRING, "price" to DataType.INT))
    private val connectionSchema = ObjectSchema(mapOf("quantity" to DataType.INT))

    private var connection: Connection? = null
    private var driver: PostgresDriver? = null

    private fun isDatabaseReachable(): Boolean {
        return try {
            DriverManager.getConnection("jdbc:postgresql://$host:$port/$database", username, password).use { true }
        } catch (e: Exception) {
            false
        }
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(isDatabaseReachable()) { "Live PostgreSQL database is not reachable at $host:$port. Skipping integration tests." }

        val conn = DriverManager.getConnection("jdbc:postgresql://$host:$port/$database", username, password)
        connection = conn
        driver = PostgresDriver(conn)

        // Reset and populate schema registry in DatabaseManager
        DatabaseManager.initCollections(
            mutableMapOf(
                CollectionRef("test_users") to CollectionModel("test_users", userSchema),
                CollectionRef("test_users", "test_orders") to CollectionModel("test_orders", orderSchema)
            )
        )
        DatabaseManager.initConnections(
            mutableMapOf(
                "test_bought" to ConnectionModel(
                    "test_bought",
                    CollectionRef("test_users"),
                    CollectionRef("test_users", "test_orders"),
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
            stmt.execute("DROP TABLE IF EXISTS \"ps_con_test_users__test_bought__test_users_test_orders\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_test_users_test_orders\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_test_users\"")
        }
    }

    @Test
    fun testPostgresDriverLifecycleAndQueries() {
        val driver = this.driver ?: return

        // 1. Create collections
        driver.createCollection(CreateCollectionInstruction(CollectionModel("test_users", userSchema)))
        driver.createCollection(
            CreateCollectionInstruction(
                CollectionModel("test_orders", orderSchema),
                parentCollection = CollectionRef("test_users")
            )
        )

        // 2. Create connection
        val connectionModel = ConnectionModel(
            name = "test_bought",
            collection1 = CollectionRef("test_users"),
            collection2 = CollectionRef("test_users", "test_orders"),
            connectionDataSchema = connectionSchema
        )
        driver.createConnection(connectionModel)

        // 3. Insert Objects
        val userUuid = UUID.randomUUID()
        val insertUserInstruction = InsertObjectInstruction(
            CollectionPath("test_users"),
            PolyDocument(mapOf("name" to PolyValue.of("Alice"), "age" to PolyValue.of(30)))
        )
        driver.insertDocument(userUuid, insertUserInstruction)

        val orderUuid = UUID.randomUUID()
        val orderPath = CollectionPath("test_users").doc(userUuid).sub("test_orders")
        val insertOrderInstruction = InsertObjectInstruction(
            orderPath,
            PolyDocument(mapOf("item" to PolyValue.of("Laptop"), "price" to PolyValue.of(1200)))
        )
        driver.insertDocument(orderUuid, insertOrderInstruction)

        // Insert Connection record directly using SQL (as connection table is updated manually or via query in app design)
        connection!!.prepareStatement(
            "INSERT INTO \"ps_con_test_users__test_bought__test_users_test_orders\" " +
                    "(\"ps_cfk_test_users\", \"ps_cfk_test_users_test_orders\", \"ps_f_quantity\") " +
                    "VALUES ('$userUuid', '$orderUuid', 5)"
        ).execute()

        // 4. Test updateObject
        val updateInstruction = UpdateObjectInstruction(
            CollectionPath("test_users").doc(userUuid),
            PolyDocument(mapOf("age" to PolyValue.of(31)))
        )
        driver.insertDocument(updateInstruction)

        // 5. Test take (Wildcard)
        // path: test_users
        val queryPath = QueryPath(QuerySegment.Collection("test_users"))
        val takeResult = driver.take(queryPath, PolyTerminal.Take(listOf(FieldRef.Wildcard("test_users"))))

        assertEquals(1, takeResult.polyData.size)
        val userRow = takeResult.polyData[0]
        assertEquals(userUuid.toString(), userRow["ps_col_test_users__id"]?.value?.toString())
        assertEquals("Alice", userRow["ps_col_test_users__name"]?.value)
        assertEquals(31, userRow["ps_col_test_users__age"]?.value) // updated age

        // 6. Test count
        val countResult = driver.count(queryPath, PolyTerminal.Count)
        assertEquals(1, countResult.count)

        // 7. Test Join query across connection
        // path: test_users - test_bought - test_orders
        val joinPath = QueryPath(
            listOf(
                QuerySegment.Collection("test_users"),
                QuerySegment.Connection("test_bought", "test_orders")
            )
        )

        val joinResult = driver.take(
            joinPath, PolyTerminal.Take(
                listOf(
                    FieldRef.Wildcard("test_users"),
                    FieldRef.Wildcard("test_bought"),
                    FieldRef.Wildcard("test_orders")
                )
            )
        )

        assertEquals(1, joinResult.polyData.size)
        val joinRow = joinResult.polyData[0]

        // Validate user fields in join
        assertEquals(userUuid.toString(), joinRow["ps_col_test_users__id"]?.value?.toString())
        assertEquals("Alice", joinRow["ps_col_test_users__name"]?.value)
        assertEquals(31, joinRow["ps_col_test_users__age"]?.value)

        // Validate connection fields in join
        // PostgreSQL truncates identifiers to 63 bytes (ps_con_test_users__test_bought__test_users_test_orders__quantity -> ps_con_test_users__test_bought__test_users_test_orders__quantit)
        val quantityKey = "ps_con_test_users__test_bought__test_users_test_orders__quantit"
        assertEquals(5, joinRow[quantityKey]?.value)

        // Validate order fields in join
        assertEquals(orderUuid.toString(), joinRow["ps_col_test_users_test_orders__id"]?.value?.toString())
        assertEquals("Laptop", joinRow["ps_col_test_users_test_orders__item"]?.value)
        assertEquals(1200, joinRow["ps_col_test_users_test_orders__price"]?.value)
    }

    @Test
    fun testPostgresDriverQueryWithConditions() {
        val driver = this.driver ?: return

        driver.createCollection(CreateCollectionInstruction(CollectionModel("test_users", userSchema)))

        val user1 = UUID.randomUUID()
        driver.insertDocument(
            user1, InsertObjectInstruction(
                CollectionPath("test_users"),
                PolyDocument(mapOf("name" to PolyValue.of("Alice"), "age" to PolyValue.of(25)))
            )
        )

        val user2 = UUID.randomUUID()
        driver.insertDocument(
            user2, InsertObjectInstruction(
                CollectionPath("test_users"),
                PolyDocument(mapOf("name" to PolyValue.of("Bob"), "age" to PolyValue.of(35)))
            )
        )

        // Query: from (test_users u where age > 30)
        val pathWithCond =
            QueryPath(QuerySegment.Collection("test_users", Condition.Comparison.GreaterThan("age", PolyValue.of(30))))
        val result = driver.take(pathWithCond, PolyTerminal.Take(listOf(FieldRef.Wildcard("test_users"))))

        assertEquals(1, result.polyData.size)
        assertEquals("Bob", result.polyData[0]["ps_col_test_users__name"]?.value)
    }
}
