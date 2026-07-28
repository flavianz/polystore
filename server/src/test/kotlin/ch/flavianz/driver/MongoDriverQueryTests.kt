package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.CollectionPath
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.query.and
import ch.flavianz.query.eq
import ch.flavianz.query.gt
import ch.flavianz.query.lt
import ch.flavianz.query.or
import ch.flavianz.query.query
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.bson.UuidRepresentation
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for MongoDriver focused on query correctness.
 *
 * Schema used throughout:
 *
 *   students  (name: STRING, gpa: INT)
 *     └── subcollection: enrollments  (semester: STRING, grade: INT)
 *
 *   courses      (title: STRING, credits: INT)
 *   departments  (name: STRING, budget: INT)
 *
 *   connections:
 *     students --[attends]--> courses       (score: INT)
 *     courses  --[belongs_to]--> departments (since: INT)
 */
class MongoDriverQueryTests {

    private val host = System.getenv("TEST_MONGO_HOST") ?: "localhost"
    private val port = (System.getenv("TEST_MONGO_PORT") ?: "27017").toInt()
    private val database = System.getenv("TEST_MONGO_DATABASE") ?: "polystore_test"

    // schemas

    private val studentSchema = mapOf("name" to DataType.STRING, "gpa" to DataType.INT)
    private val enrollmentSchema = mapOf("semester" to DataType.STRING, "grade" to DataType.INT)
    private val courseSchema = mapOf("title" to DataType.STRING, "credits" to DataType.INT)
    private val departmentSchema = mapOf("name" to DataType.STRING, "budget" to DataType.INT)
    private val attendsSchema = mapOf("score" to DataType.INT)
    private val belongsToSchema = mapOf("since" to DataType.INT)

    // collections

    private val studentsModel = CollectionModel("students", studentSchema, mutableListOf("enrollments"), null)
    private val enrollmentsModel = CollectionModel("enrollments", enrollmentSchema, mutableListOf(), "students")
    private val coursesModel = CollectionModel("courses", courseSchema, mutableListOf(), null)
    private val departmentsModel = CollectionModel("departments", departmentSchema, mutableListOf(), null)


    // ids

    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val carolId = UUID.randomUUID()

    private val mathId = UUID.randomUUID()
    private val historyId = UUID.randomUUID()
    private val physicsId = UUID.randomUUID()

    private val scienceDeptId = UUID.randomUUID()
    private val humanitiesDeptId = UUID.randomUUID()

    // state

    private var mongoDatabase: MongoDatabase? = null
    private var driver: MongoDriver? = null

    // lifecycle

    private fun isDatabaseReachable(): Boolean = try {
        MongoClients.create("mongodb://$host:$port")
            .use { it.getDatabase(database).runCommand(org.bson.Document("ping", 1)) }
        true
    } catch (_: Exception) {
        false
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(isDatabaseReachable()) {
            "Live MongoDB is not reachable at $host:$port. Skipping integration tests."
        }

        val settings = MongoClientSettings.builder().applyConnectionString(ConnectionString("mongodb://$host:$port"))
            .uuidRepresentation(
                UuidRepresentation.STANDARD
            ).build()
        val client = MongoClients.create(settings)
        mongoDatabase = client.getDatabase(database)
        driver = MongoDriver(mongoDatabase!!)

        DatabaseManager.initCollections(
            listOf(
                studentsModel,
                enrollmentsModel,
                coursesModel,
                departmentsModel
            )
        )
        DatabaseManager.initConnections(
            listOf(
                ConnectionModel("attends", "students", "courses", attendsSchema),
                ConnectionModel("belongs_to", "courses", "departments", belongsToSchema),
            )
        )

        cleanupCollections()
        createSchema()
        populateData()
    }

    @AfterTest
    fun tearDown() {
        cleanupCollections()
    }

    // ── Schema & data helpers ─────────────────────────────────────────────────

    private fun createSchema() {
        val d = driver!!
        d.createCollection("students", studentSchema)
        d.createCollection("enrollments", enrollmentSchema, "students")
        d.createCollection("courses", courseSchema)
        d.createCollection("departments", departmentSchema)
        d.createConnection(ConnectionModel("attends", "students", "courses", attendsSchema))
        d.createConnection(ConnectionModel("belongs_to", "courses", "departments", belongsToSchema))
    }

    private fun populateData() {
        val d = driver!!

        // Students: Alice gpa=4, Bob gpa=3, Carol gpa=2
        d.insertDocument(
            studentsModel,
            aliceId,
            mapOf("name" to PolyValue.of("Alice"), "gpa" to PolyValue.of(4))

        )
        d.insertDocument(
            studentsModel,
            bobId,
            mapOf("name" to PolyValue.of("Bob"), "gpa" to PolyValue.of(3))

        )
        d.insertDocument(
            studentsModel,
            carolId,
            mapOf("name" to PolyValue.of("Carol"), "gpa" to PolyValue.of(2))

        )

        // Enrollments (subcollection of students)
        // Alice: Fall grade=90, Spring grade=85; Bob: Fall grade=70; Carol: none
        d.insertDocument(
            enrollmentsModel,
            UUID.randomUUID(),
            mapOf("semester" to PolyValue.of("Fall"), "grade" to PolyValue.of(90)), aliceId

        )
        d.insertDocument(
            enrollmentsModel,
            UUID.randomUUID(),
            mapOf("semester" to PolyValue.of("Spring"), "grade" to PolyValue.of(85)), aliceId

        )
        d.insertDocument(
            enrollmentsModel,
            UUID.randomUUID(),
            mapOf("semester" to PolyValue.of("Fall"), "grade" to PolyValue.of(70)), bobId

        )

        // Courses
        d.insertDocument(
            coursesModel,
            mathId,
            mapOf("title" to PolyValue.of("Math"), "credits" to PolyValue.of(4))

        )
        d.insertDocument(
            coursesModel,
            historyId,
            mapOf("title" to PolyValue.of("History"), "credits" to PolyValue.of(3))

        )
        d.insertDocument(
            coursesModel,
            physicsId,
            mapOf("title" to PolyValue.of("Physics"), "credits" to PolyValue.of(4))

        )

        // Departments
        d.insertDocument(
            departmentsModel,
            scienceDeptId,
            mapOf("name" to PolyValue.of("Science"), "budget" to PolyValue.of(500))

        )
        d.insertDocument(
            departmentsModel,
            humanitiesDeptId,
            mapOf("name" to PolyValue.of("Humanities"), "budget" to PolyValue.of(200))

        )

        // attends: Alice→Math(95), Alice→History(80), Bob→Math(60), Carol→Physics(75)
        d.insertConnection(
            ConnectionModel("attends", "students", "courses", attendsSchema),
            "students", aliceId, "courses", mathId,
            mapOf("score" to PolyValue.of(95))
        )
        d.insertConnection(
            ConnectionModel("attends", "students", "courses", attendsSchema),
            "students", aliceId, "courses", historyId,
            mapOf("score" to PolyValue.of(80))
        )
        d.insertConnection(
            ConnectionModel("attends", "students", "courses", attendsSchema),
            "students", bobId, "courses", mathId,
            mapOf("score" to PolyValue.of(60))
        )
        d.insertConnection(
            ConnectionModel("attends", "students", "courses", attendsSchema),
            "students", carolId, "courses", physicsId,
            mapOf("score" to PolyValue.of(75))
        )

        // belongs_to: Math→Science(2000), Physics→Science(2010), History→Humanities(1990)
        d.insertConnection(
            ConnectionModel("belongs_to", "courses", "departments", belongsToSchema),
            "courses", mathId, "departments", scienceDeptId,
            mapOf("since" to PolyValue.of(2000))
        )
        d.insertConnection(
            ConnectionModel("belongs_to", "courses", "departments", belongsToSchema),
            "courses", physicsId, "departments", scienceDeptId,
            mapOf("since" to PolyValue.of(2010))
        )
        d.insertConnection(
            ConnectionModel("belongs_to", "courses", "departments", belongsToSchema),
            "courses", historyId, "departments", humanitiesDeptId,
            mapOf("since" to PolyValue.of(1990))
        )
    }

    private fun cleanupCollections() {
        mongoDatabase?.getCollection("students")?.drop()
        mongoDatabase?.getCollection("enrollments")?.drop()
        mongoDatabase?.getCollection("courses")?.drop()
        mongoDatabase?.getCollection("departments")?.drop()
    }

    private fun PolyData.str(segment: String, field: String) =
        this["$segment.$field"]?.value

    private fun PolyData.int(segment: String, field: String) =
        this["$segment.$field"]?.value as? Int

    // ── Tests: simple collection queries ─────────────────────────────────────

    @Test
    fun `take all students returns all three`() {
        val result = driver!!.get(query { collection("students") })
        assertEquals(3, result.data.size)
        val names = result.data.map { it.str("students", "name") }.toSet()
        assertEquals(setOf("Alice", "Bob", "Carol"), names)
    }

    @Test
    fun `take students with gpa greater than 2 returns Alice and Bob`() {
        val result = driver!!.get(query { collection("students", "gpa" gt 2) })
        assertEquals(2, result.data.size)
        val names = result.data.map { it.str("students", "name") }.toSet()
        assertEquals(setOf("Alice", "Bob"), names)
    }

    @Test
    fun `take students with gpa less than 4 returns Bob and Carol`() {
        val result = driver!!.get(query { collection("students", "gpa" lt 4) })
        assertEquals(2, result.data.size)
        val names = result.data.map { it.str("students", "name") }.toSet()
        assertEquals(setOf("Bob", "Carol"), names)
    }

    @Test
    fun `take students with compound AND condition returns only Bob`() {
        // gpa > 2 AND gpa < 4 → Bob (gpa=3)
        val result = driver!!.get(query { collection("students", ("gpa" gt 2) and ("gpa" lt 4)) })
        assertEquals(1, result.data.size)
        assertEquals("Bob", result.data[0].str("students", "name"))
        assertEquals(3, result.data[0].int("students", "gpa"))
    }

    @Test
    fun `take students with compound OR condition returns Alice and Carol`() {
        // gpa == 4 OR gpa == 2 → Alice and Carol
        val result = driver!!.get(query { collection("students", ("gpa" eq 4) or ("gpa" eq 2)) })
        assertEquals(2, result.data.size)
        val names = result.data.map { it.str("students", "name") }.toSet()
        assertEquals(setOf("Alice", "Carol"), names)
    }

    @Test
    fun `take students with condition matching nobody returns empty`() {
        val result = driver!!.get(query { collection("students", "gpa" gt 100) })
        assertTrue(result.data.isEmpty())
    }

    // ── Tests: subcollection (Kinder) queries ─────────────────────────────────

    @Test
    fun `take all enrollments across all students returns three`() {
        // Alice has 2, Bob has 1, Carol has 0 → 3 total
        val result = driver!!.get(query {
            collection("students")
            collection("enrollments")
        })
        assertEquals(3, result.data.size)
    }

    @Test
    fun `take enrollments filtered by parent student`() {
        // Only Alice's enrollments → 2
        val result = driver!!.get(query {
            collection("students", "name" eq "Alice")
            collection("enrollments")
        })
        assertEquals(2, result.data.size)
        result.data.forEach { assertEquals("Alice", it.str("students", "name")) }
    }

    @Test
    fun `take enrollments filtered by child condition`() {
        // Only Fall enrollments → Alice-Fall and Bob-Fall → 2 rows
        val result = driver!!.get(query {
            collection("students")
            collection("enrollments", "semester" eq "Fall")
        })
        assertEquals(2, result.data.size)
        result.data.forEach { assertEquals("Fall", it.str("enrollments", "semester")) }
    }

    @Test
    fun `take enrollments with high grade returns only Alice Fall`() {
        // grade > 85 → only Alice Fall (grade=90)
        val result = driver!!.get(query {
            collection("students")
            collection("enrollments", "grade" gt 85)
        })
        assertEquals(1, result.data.size)
        assertEquals("Alice", result.data[0].str("students", "name"))
        assertEquals(90, result.data[0].int("enrollments", "grade"))
    }

    @Test
    fun `student with no enrollments does not appear in subcollection query`() {
        // Carol has no enrollments
        val result = driver!!.get(query {
            collection("students")
            collection("enrollments")
        })
        val names = result.data.map { it.str("students", "name") }
        assertFalse(names.contains("Carol"))
    }

    @Test
    fun `subcollection rows carry correct parent fields without cross-contamination`() {
        val result = driver!!.get(query {
            collection("students")
            collection("enrollments")
        })
        val bobRows = result.data.filter { it.str("students", "name") == "Bob" }
        assertEquals(1, bobRows.size)
        // Bob's row must carry Bob's gpa, not Alice's
        assertEquals(3, bobRows[0].int("students", "gpa"))
        assertEquals(70, bobRows[0].int("enrollments", "grade"))
    }

    // ── Tests: single-hop connection queries ─────────────────────────────────

    @Test
    fun `take students with their attended courses returns 4 rows`() {
        // Alice-Math, Alice-History, Bob-Math, Carol-Physics
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses")
        })
        assertEquals(4, result.data.size)
    }

    @Test
    fun `join row contains correct fields from all three segments`() {
        // Alice attends Math with score=95
        val result = driver!!.get(query {
            collection("students", "name" eq "Alice")
            connection("attends", "courses", connectionCondition = "score" eq 95)
        })
        assertEquals(1, result.data.size)
        val row = result.data[0]
        assertEquals("Alice", row.str("students", "name"))
        assertEquals(4, row.int("students", "gpa"))
        assertEquals(95, row.int("attends", "score"))
        assertEquals("Math", row.str("courses", "title"))
        assertEquals(4, row.int("courses", "credits"))
    }

    @Test
    fun `filter on collection side of join returns only matching student rows`() {
        // Alice only → 2 rows
        val result = driver!!.get(query {
            collection("students", "name" eq "Alice")
            connection("attends", "courses")
        })
        assertEquals(2, result.data.size)
        result.data.forEach { assertEquals("Alice", it.str("students", "name")) }
    }

    @Test
    fun `filter on connection data returns only high-scoring rows`() {
        // score > 70 → Alice-Math(95), Alice-History(80), Carol-Physics(75); excludes Bob-Math(60)
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses", connectionCondition = "score" gt 70)
        })
        assertEquals(3, result.data.size)
        result.data.forEach {
            assertTrue((it.int("attends", "score") ?: 0) > 70)
        }
    }

    @Test
    fun `filter on both collection and connection returns single precise row`() {
        // Bob AND score < 70 → Bob-Math(60)
        val result = driver!!.get(query {
            collection("students", "name" eq "Bob")
            connection("attends", "courses", connectionCondition = "score" lt 70)
        })
        assertEquals(1, result.data.size)
        assertEquals("Bob", result.data[0].str("students", "name"))
        assertEquals(60, result.data[0].int("attends", "score"))
        assertEquals("Math", result.data[0].str("courses", "title"))
    }

    @Test
    fun `filter on target collection of join returns only matching course rows`() {
        // Only courses with credits == 4 (Math, Physics) → Alice-Math, Bob-Math, Carol-Physics
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses", collectionCondition = "credits" eq 4)
        })
        assertEquals(3, result.data.size)
        val titles = result.data.map { it.str("courses", "title") }.toSet()
        assertEquals(setOf("Math", "Physics"), titles)
    }

    @Test
    fun `student with no connections does not appear in join result`() {
        val lonelyId = UUID.randomUUID()
        driver!!.insertDocument(
            studentsModel,
            lonelyId,
            mapOf("name" to PolyValue.of("Lonely"), "gpa" to PolyValue.of(1))

        )
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses")
        })
        assertFalse(result.data.any { it.str("students", "name") == "Lonely" })
    }

    // ── Tests: two-hop connection queries ────────────────────────────────────

    @Test
    fun `two-hop join returns correct total row count`() {
        // Alice-Math-Science, Alice-History-Humanities, Bob-Math-Science, Carol-Physics-Science → 4
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses")
            connection("belongs_to", "departments")
        })
        assertEquals(4, result.data.size)
    }

    @Test
    fun `two-hop join row contains fields from all five segments`() {
        // Alice attends Math (score=95), Math belongs_to Science (since=2000)
        val result = driver!!.get(query {
            collection("students", "name" eq "Alice")
            connection("attends", "courses", connectionCondition = "score" eq 95)
            connection("belongs_to", "departments")
        })
        assertEquals(1, result.data.size)
        val row = result.data[0]
        assertEquals("Alice", row.str("students", "name"))
        assertEquals(95, row.int("attends", "score"))
        assertEquals("Math", row.str("courses", "title"))
        assertEquals(2000, row.int("belongs_to", "since"))
        assertEquals("Science", row.str("departments", "name"))
    }

    @Test
    fun `two-hop filter on middle collection narrows result correctly`() {
        // credits == 4 in middle → Math and Physics only; History (credits=3) excluded
        // Alice-Math-Science, Bob-Math-Science, Carol-Physics-Science → 3 rows
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses", collectionCondition = "credits" eq 4)
            connection("belongs_to", "departments")
        })
        assertEquals(3, result.data.size)
        result.data.forEach { assertEquals("Science", it.str("departments", "name")) }
    }

    @Test
    fun `two-hop filter on final department filters end of chain`() {
        // Only Humanities at the end → only Alice-History-Humanities
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses")
            connection("belongs_to", "departments", collectionCondition = "name" eq "Humanities")
        })
        assertEquals(1, result.data.size)
        assertEquals("Alice", result.data[0].str("students", "name"))
        assertEquals("History", result.data[0].str("courses", "title"))
        assertEquals("Humanities", result.data[0].str("departments", "name"))
    }

    @Test
    fun `two-hop compound conditions across all hops return exact single row`() {
        // Alice AND score > 90 AND Science → Alice-Math(95)-Science
        val result = driver!!.get(query {
            collection("students", "name" eq "Alice")
            connection("attends", "courses", connectionCondition = "score" gt 90)
            connection("belongs_to", "departments", collectionCondition = "name" eq "Science")
        })
        assertEquals(1, result.data.size)
        assertEquals("Alice", result.data[0].str("students", "name"))
        assertEquals(95, result.data[0].int("attends", "score"))
        assertEquals("Math", result.data[0].str("courses", "title"))
        assertEquals("Science", result.data[0].str("departments", "name"))
    }

    // ── Tests: structural correctness ─────────────────────────────────────────

    @Test
    fun `each result row has a unique combination of ids across hops`() {
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses")
            connection("belongs_to", "departments")
        })

        // Use FieldRef.Named to pull _id fields for uniqueness check
        val uniqueKeys = result.data.map { row ->
            Triple(
                row["students._id"]?.value,
                row["courses._id"]?.value,
                row["departments._id"]?.value
            )
        }.toSet()

        assertEquals(result.data.size, uniqueKeys.size)
    }

    @Test
    fun `result rows carry no cross-contamination between students`() {
        val result = driver!!.get(query {
            collection("students")
            connection("attends", "courses")
        })
        val bobRows = result.data.filter { it.str("students", "name") == "Bob" }
        assertTrue(bobRows.isNotEmpty())
        bobRows.forEach { row ->
            assertEquals(
                3, row.int("students", "gpa"),
                "Bob's row should carry Bob's gpa=3, not another student's"
            )
        }
    }

    @Test
    fun `collection-only query is unaffected by presence of connection data`() {
        val result = driver!!.get(query { collection("students") })
        assertEquals(3, result.data.size)
    }

    @Test
    fun `updating a student field is reflected in subsequent queries`() {
        // Update Alice's gpa from 4 to 5, then query
        driver!!.updateDocument(
            CollectionPath("students").doc(aliceId),
            mapOf("gpa" to PolyValue.of(5))

        )
        val result = driver!!.get(query { collection("students", "name" eq "Alice") })
        assertEquals(1, result.data.size)
        assertEquals(5, result.data[0].int("students", "gpa"))
    }
}