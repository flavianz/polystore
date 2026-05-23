package ch.flavianz.instructions

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyDocument
import ch.flavianz.data.PolyValue
import ch.flavianz.driver.DriverManager
import ch.flavianz.model.*
import ch.flavianz.query.QueryParser
import java.util.UUID
import kotlin.test.*

class InstructionTests {

    private val handler = InstructionHandler()

    @BeforeTest
    fun setUp() {
        // Initialize DriverManager with a null PostgresDriver if not already initialized
        try {
            DriverManager.getInstance()
        } catch (e: IllegalStateException) {
            DriverManager.initialize {
                // postgresDriver remains null for unit tests
            }
        }

        // Reset DatabaseManager state before each test
        DatabaseManager.initCollections(mutableMapOf())
        DatabaseManager.initConnections(mutableMapOf())
    }

    @Test
    fun testCreateCollectionInstructionSuccess() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
        val collectionModel = CollectionModel("users", schema)
        val instruction = CreateCollectionInstruction(collectionModel)

        handler.handle(instruction)

        assertTrue(DatabaseManager.existsCollection(CollectionRef("users")))
        val registeredModel = DatabaseManager.getCollectionModel(CollectionRef("users"))
        assertEquals("users", registeredModel.name)
        assertEquals(schema, registeredModel.schema)
    }

    @Test
    fun testCreateCollectionInstructionSubcollectionSuccess() {
        val parentSchema = ObjectSchema(mapOf("name" to DataType.STRING))
        val parentModel = CollectionModel("companies", parentSchema)
        handler.handle(CreateCollectionInstruction(parentModel))

        val childSchema = ObjectSchema(mapOf("title" to DataType.STRING))
        val childModel = CollectionModel("jobs", childSchema)
        val instruction = CreateCollectionInstruction(childModel, parentCollection = CollectionRef("companies"))

        handler.handle(instruction)

        val childRef = CollectionRef("companies").sub("jobs")
        assertTrue(DatabaseManager.existsCollection(childRef))
        val registeredChild = DatabaseManager.getCollectionModel(childRef)
        assertEquals("jobs", registeredChild.name)
    }

    @Test
    fun testCreateCollectionInstructionDuplicateRoot() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        val model = CollectionModel("users", schema)
        handler.handle(CreateCollectionInstruction(model))

        assertFailsWith<IllegalStateException> {
            handler.handle(CreateCollectionInstruction(model))
        }
    }

    @Test
    fun testCreateCollectionInstructionParentDoesNotExist() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        val model = CollectionModel("jobs", schema)
        val instruction = CreateCollectionInstruction(model, parentCollection = CollectionRef("nonexistent"))

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testCreateConnectionInstructionSuccess() {
        // Create two collections
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))
        handler.handle(CreateCollectionInstruction(CollectionModel("groups", schema)))

        // Create connection
        val connectionDataSchema = ObjectSchema(mapOf("role" to DataType.STRING))
        val connectionModel = ConnectionModel(
            name = "belongs_to",
            collection1 = CollectionRef("users"),
            collection2 = CollectionRef("groups"),
            connectionData = connectionDataSchema
        )
        val instruction = CreateConnectionInstruction(connectionModel)

        handler.handle(instruction)

        val registeredConnection = DatabaseManager.getConnectionModel("belongs_to")
        assertEquals("belongs_to", registeredConnection.name)
        assertEquals(CollectionRef("users"), registeredConnection.collection1)
        assertEquals(CollectionRef("groups"), registeredConnection.collection2)
        assertEquals(connectionDataSchema, registeredConnection.connectionData)
    }

    @Test
    fun testCreateConnectionInstructionMissingCollection() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        val connectionModel = ConnectionModel(
            name = "belongs_to",
            collection1 = CollectionRef("users"),
            collection2 = CollectionRef("nonexistent"),
            connectionData = ObjectSchema(emptyMap())
        )

        assertFailsWith<IllegalStateException> {
            handler.handle(CreateConnectionInstruction(connectionModel))
        }
    }

    @Test
    fun testCreateConnectionInstructionDuplicateName() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))
        handler.handle(CreateCollectionInstruction(CollectionModel("groups", schema)))

        val connectionModel = ConnectionModel(
            name = "belongs_to",
            collection1 = CollectionRef("users"),
            collection2 = CollectionRef("groups"),
            connectionData = ObjectSchema(emptyMap())
        )

        handler.handle(CreateConnectionInstruction(connectionModel))

        assertFailsWith<IllegalStateException> {
            handler.handle(CreateConnectionInstruction(connectionModel))
        }
    }

    @Test
    fun testInsertObjectInstructionSuccess() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        val doc = PolyDocument(mapOf(
            "name" to PolyValue.of("Alice"),
            "age" to PolyValue.of(30)
        ))
        val instruction = InsertObjectInstruction(CollectionPath("users"), doc)

        // Should not throw, driver execute is no-op
        handler.handle(instruction)
    }

    @Test
    fun testInsertObjectInstructionMissingCollection() {
        val doc = PolyDocument(mapOf("name" to PolyValue.of("Alice")))
        val instruction = InsertObjectInstruction(CollectionPath("users"), doc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testInsertObjectInstructionTypeMismatch() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        // 'age' is passed as String instead of Int
        val doc = PolyDocument(mapOf(
            "name" to PolyValue.of("Alice"),
            "age" to PolyValue.of("thirty")
        ))
        val instruction = InsertObjectInstruction(CollectionPath("users"), doc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testInsertObjectInstructionFieldCountMismatch() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        // Missing 'age' field
        val doc = PolyDocument(mapOf(
            "name" to PolyValue.of("Alice")
        ))
        val instruction = InsertObjectInstruction(CollectionPath("users"), doc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testUpdateObjectInstructionSuccess() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        val docId = UUID.randomUUID()
        val updateDoc = PolyDocument(mapOf(
            "age" to PolyValue.of(31)
        ))
        val path = CollectionPath("users").doc(docId)
        val instruction = UpdateObjectInstruction(path, updateDoc)

        // Should not throw, driver execute is no-op
        handler.handle(instruction)
    }

    @Test
    fun testUpdateObjectInstructionMissingCollection() {
        val docId = UUID.randomUUID()
        val updateDoc = PolyDocument(mapOf("age" to PolyValue.of(31)))
        val path = CollectionPath("users").doc(docId)
        val instruction = UpdateObjectInstruction(path, updateDoc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testUpdateObjectInstructionUnknownField() {
        val schema = ObjectSchema(mapOf("name" to DataType.STRING, "age" to DataType.INT))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        val docId = UUID.randomUUID()
        val updateDoc = PolyDocument(mapOf(
            "nonexistent" to PolyValue.of("value")
        ))
        val path = CollectionPath("users").doc(docId)
        val instruction = UpdateObjectInstruction(path, updateDoc)

        assertFailsWith<IllegalStateException> {
            handler.handle(instruction)
        }
    }

    @Test
    fun testQueryInstructionValidationSuccessButDriverNotConnected() {
        // Query: from users take users.name
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        val parser = QueryParser("from users take users.name")
        val queryInstruction = QueryInstruction(parser.parse())

        // The validation passes but because driver is null it throws NotImplementedError
        assertFailsWith<NotImplementedError> {
            handler.handle(queryInstruction)
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
        val schema = ObjectSchema(mapOf("name" to DataType.STRING))
        handler.handle(CreateCollectionInstruction(CollectionModel("users", schema)))

        // Query filtering by an unknown field
        val parser = QueryParser("from (users u where age = 30) take u.name")
        val queryInstruction = QueryInstruction(parser.parse())

        assertFailsWith<IllegalArgumentException> {
            handler.handle(queryInstruction)
        }
    }
}
