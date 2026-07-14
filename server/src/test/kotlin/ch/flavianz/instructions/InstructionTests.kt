package ch.flavianz.instructions

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.model.*
import ch.flavianz.query.QueryParser
import java.util.UUID
import kotlin.test.*

class InstructionTests {

    private val handler = InstructionHandler()

    @BeforeTest
    fun setUp() {
        // Reset DatabaseManager state before each test
        DatabaseManager.initCollections(listOf())
        DatabaseManager.initConnections(listOf())
    }

    @Test
    fun testCreateCollectionInstructionSuccess() {
        val schema = mapOf("name" to DataType.STRING, "age" to DataType.INT)

        DatabaseManager.createCollection("users", schema)

        assertTrue(DatabaseManager.existsCollection(CollectionRef("users")))
        val registeredModel = DatabaseManager.getCollectionModel(CollectionRef("users"))
        assertEquals("users", registeredModel.name)
        assertEquals(schema, registeredModel.schema)
    }

    @Test
    fun testCreateCollectionInstructionSubcollectionSuccess() {
        val parentSchema = mapOf("name" to DataType.STRING)
        DatabaseManager.createCollection("companies", parentSchema)

        val childSchema = mapOf("title" to DataType.STRING)
        DatabaseManager.createCollection("jobs", childSchema, "companies")

        val childRef = CollectionRef("companies").sub("jobs")
        assertTrue(DatabaseManager.existsCollection(childRef))
        val registeredChild = DatabaseManager.getCollectionModel(childRef)
        assertEquals("jobs", registeredChild.name)
    }

    @Test
    fun testCreateCollectionInstructionDuplicateRoot() {
        val schema = mapOf("name" to DataType.STRING)
        DatabaseManager.createCollection("users", schema)

        assertFailsWith<IllegalStateException> {
            DatabaseManager.createCollection("users", schema)
        }
    }

    @Test
    fun testCreateCollectionInstructionParentDoesNotExist() {
        val schema = mapOf("name" to DataType.STRING)

        assertFailsWith<IllegalStateException> {
            DatabaseManager.createCollection("jobs", schema, "nonexistent")
        }
    }

    @Test
    fun testCreateConnectionInstructionSuccess() {
        // Create two collections
        val schema = mapOf("name" to DataType.STRING)
        DatabaseManager.createCollection("users", schema)
        DatabaseManager.createCollection("groups", schema)

        // Create connection
        val connectionDataSchema = mapOf("role" to DataType.STRING)
        val connectionModel = ConnectionModel(
            name = "belongs_to",
            collection1Name = "users",
            collection2Name = "groups",
            connectionDataSchema = connectionDataSchema
        )
        val instruction = CreateConnectionInstruction(connectionModel)

        handler.handle(instruction)

        val registeredConnection = DatabaseManager.getConnectionModel("belongs_to")
        assertEquals("belongs_to", registeredConnection.name)
        assertEquals("users", registeredConnection.collection1Name)
        assertEquals("groups", registeredConnection.collection2Name)
        assertEquals(connectionDataSchema, registeredConnection.connectionDataSchema)
    }

    @Test
    fun testCreateConnectionInstructionMissingCollection() {
        val schema = mapOf("name" to DataType.STRING)
        DatabaseManager.createCollection("users", schema)

        val connectionModel = ConnectionModel(
            name = "belongs_to",
            collection1Name = "users",
            collection2Name = "nonexistent",
            connectionDataSchema = (emptyMap())
        )

        assertFailsWith<IllegalStateException> {
            handler.handle(CreateConnectionInstruction(connectionModel))
        }
    }

    @Test
    fun testCreateConnectionInstructionDuplicateName() {
        val schema = mapOf("name" to DataType.STRING)
        DatabaseManager.createCollection("users", schema)
        DatabaseManager.createCollection("groups", schema)

        val connectionModel = ConnectionModel(
            name = "belongs_to",
            collection1Name = "users",
            collection2Name = "groups",
            connectionDataSchema = (emptyMap())
        )

        handler.handle(CreateConnectionInstruction(connectionModel))

        assertFailsWith<IllegalStateException> {
            handler.handle(CreateConnectionInstruction(connectionModel))
        }
    }

    @Test
    fun testInsertObjectInstructionSuccess() {
        val schema = mapOf("name" to DataType.STRING, "age" to DataType.INT)
        DatabaseManager.createCollection("users", schema)

        val doc = mapOf(
            "name" to PolyValue.of("Alice"),
            "age" to PolyValue.of(30)
        )

        // Should not throw, driver execute is no-op
        DatabaseManager.insertDocument("users", doc)
    }

    @Test
    fun testInsertObjectInstructionMissingCollection() {
        val doc = mapOf("name" to PolyValue.of("Alice"))

        assertFailsWith<IllegalStateException> {
            DatabaseManager.insertDocument("users", doc)
        }
    }

    @Test
    fun testInsertObjectInstructionTypeMismatch() {
        val schema = mapOf("name" to DataType.STRING, "age" to DataType.INT)
        DatabaseManager.createCollection("users", schema)

        // 'age' is passed as String instead of Int
        val doc = mapOf(
            "name" to PolyValue.of("Alice"),
            "age" to PolyValue.of("thirty")
        )

        assertFailsWith<IllegalStateException> {
            DatabaseManager.insertDocument("users", doc)
        }
    }

    @Test
    fun testInsertObjectInstructionFieldCountMismatch() {
        val schema = mapOf("name" to DataType.STRING, "age" to DataType.INT)
        DatabaseManager.createCollection("users", schema)

        // Missing 'age' field
        val doc = mapOf(
            "name" to PolyValue.of("Alice")
        )

        assertFailsWith<IllegalStateException> {
            DatabaseManager.insertDocument("users", doc)
        }
    }

    @Test
    fun testUpdateObjectInstructionSuccess() {
        val schema = mapOf("name" to DataType.STRING, "age" to DataType.INT)
        DatabaseManager.createCollection("users", schema)

        val docId = UUID.randomUUID()
        val updateDoc = mapOf(
            "age" to PolyValue.of(31)
        )
        val path = CollectionPath("users").doc(docId)
        val instruction = UpdateObjectInstruction(path, updateDoc)

        // Should not throw, driver execute is no-op
        handler.handle(instruction)
    }

    @Test
    fun testUpdateObjectInstructionMissingCollection() {
        val docId = UUID.randomUUID()
        val updateDoc = mapOf("age" to PolyValue.of(31))
        val path = CollectionPath("users").doc(docId)
        val instruction = UpdateObjectInstruction(path, updateDoc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testUpdateObjectInstructionUnknownField() {
        val schema = mapOf("name" to DataType.STRING, "age" to DataType.INT)
        DatabaseManager.createCollection("users", schema)

        val docId = UUID.randomUUID()
        val updateDoc = mapOf(
            "nonexistent" to PolyValue.of("value")
        )
        val path = CollectionPath("users").doc(docId)
        val instruction = UpdateObjectInstruction(path, updateDoc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testQueryInstructionValidationFailureNonexistentCollection() {
        val parser = QueryParser("from nonexistent take nonexistent.name")
        val queryInstruction = QueryInstruction(parser.parse())

        assertFailsWith<IllegalStateException> {
            handler.handle(queryInstruction)
        }
    }

    @Test
    fun testQueryInstructionValidationFailureInvalidFieldInCondition() {
        val schema = mapOf("name" to DataType.STRING)
        DatabaseManager.createCollection("users", schema)

        // Query filtering by an unknown field
        val parser = QueryParser("from (users u where age = 30) take u.name")
        val queryInstruction = QueryInstruction(parser.parse())

        assertFailsWith<IllegalArgumentException> {
            handler.handle(queryInstruction)
        }
    }
}
