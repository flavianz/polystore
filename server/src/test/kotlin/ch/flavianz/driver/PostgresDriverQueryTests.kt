package ch.flavianz.driver

import core.DatabaseManager
import query.and
import query.eq
import query.gt
import query.lt
import query.or
import ch.flavianz.query.get
import driver.PostgresDriver
import model.CollectionModel
import model.ConnectionModel
import model.DataType
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for PostgresDriver focused on query correctness.
 *
 * Schema used throughout:
 *
 *   students  (name: STRING, gpa: INT)
 *     └── children: enrollments  (semester: STRING, grade: INT)
 *
 *   courses   (title: STRING, credits: INT)
 *
 *   departments (name: STRING, budget: INT)
 *
 *   connections:
 *     students --[attends]--> courses    (score: INT)
 *     courses  --[belongs_to]--> departments  (since: INT)
 */
class PostgresDriverQueryTests {

    private val host = System.getenv("TEST_DB_HOST") ?: "localhost"
    private val port = (System.getenv("TEST_DB_PORT") ?: "5432").toInt()
    private val database = System.getenv("TEST_DB_DATABASE") ?: "polystore_test"
    private val username = System.getenv("TEST_DB_USERNAME") ?: "postgres"
    private val password = System.getenv("TEST_DB_PASSWORD") ?: "password"

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

    // Students
    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val carolId = UUID.randomUUID()

    // Courses
    private val mathId = UUID.randomUUID()
    private val historyId = UUID.randomUUID()
    private val physicsId = UUID.randomUUID()

    // Departments
    private val scienceDeptId = UUID.randomUUID()
    private val humanitiesDeptId = UUID.randomUUID()

    // ── State ────────────────────────────────────────────────────────────────

    private var connection: Connection? = null
    private var driver: PostgresDriver? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private fun isDatabaseReachable(): Boolean = try {
        DriverManager.getConnection("jdbc:postgresql://$host:$port/$database", username, password).use { true }
    } catch (_: Exception) {
        false
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(isDatabaseReachable()) {
            "Live PostgreSQL database is not reachable at $host:$port. Skipping integration tests."
        }

        val conn = DriverManager.getConnection("jdbc:postgresql://$host:$port/$database", username, password)
        connection = conn
        driver = PostgresDriver(conn)
        driver?.init()

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

        cleanupTables()
        createSchema()
        populateData()
    }

    @AfterTest
    fun tearDown() {
        if (connection != null && !connection!!.isClosed) {
            cleanupTables()
            connection!!.close()
        }
    }

    // ── Schema & data helpers ─────────────────────────────────────────────────

    private fun createSchema() {
        val d = driver!!
        d.createCollection("students", studentSchema)
        d.createCollection(
            "enrollments", enrollmentSchema,
            parentCollectionName = "students"
        )
        d.createCollection("courses", courseSchema)
        d.createCollection("departments", departmentSchema)
        d.createConnection(ConnectionModel("attends", "students", "courses", attendsSchema))
        d.createConnection(ConnectionModel("belongs_to", "courses", "departments", belongsToSchema))
    }

    private fun populateData() {
        val d = driver!!

        // Students
        // Alice: gpa=4, Bob: gpa=3, Carol: gpa=2
        d.insertDocument(
            studentsModel,
            aliceId, mapOf("name" to "Alice", "gpa" to 4)
        )
        d.insertDocument(
            studentsModel,
            bobId, mapOf("name" to "Bob", "gpa" to 3)
        )
        d.insertDocument(
            studentsModel,
            carolId, mapOf("name" to "Carol", "gpa" to 2)
        )

        // Enrollments (children of students)
        // Alice has two enrollments; Bob has one; Carol has none
        d.insertDocument(
            enrollmentsModel,
            UUID.randomUUID(), mapOf("semester" to "Fall", "grade" to 90), aliceId
        )
        d.insertDocument(
            enrollmentsModel,
            UUID.randomUUID(), mapOf("semester" to "Spring", "grade" to 85), aliceId
        )
        d.insertDocument(
            enrollmentsModel,
            UUID.randomUUID(), mapOf("semester" to "Fall", "grade" to 70), bobId
        )

        // Courses
        d.insertDocument(
            coursesModel,
            mathId, mapOf("title" to "Math", "credits" to 4)
        )
        d.insertDocument(
            coursesModel,
            historyId, mapOf("title" to "History", "credits" to 3)
        )
        d.insertDocument(
            coursesModel,
            physicsId, mapOf("title" to "Physics", "credits" to 4)
        )

        // Departments
        d.insertDocument(
            departmentsModel,
            scienceDeptId, mapOf("name" to "Science", "budget" to 500)
        )
        d.insertDocument(
            departmentsModel,
            humanitiesDeptId, mapOf("name" to "Humanities", "budget" to 200)
        )

        // attends connections
        // Alice attends Math (score=95), Alice attends History (score=80)
        // Bob   attends Math (score=60)
        // Carol attends Physics (score=75)
        insertConnection("students", "attends", "courses", aliceId, mathId, mapOf("score" to 95))
        insertConnection("students", "attends", "courses", aliceId, historyId, mapOf("score" to 80))
        insertConnection("students", "attends", "courses", bobId, mathId, mapOf("score" to 60))
        insertConnection("students", "attends", "courses", carolId, physicsId, mapOf("score" to 75))

        // belongs_to connections
        // Math → Science (since=2000), Physics → Science (since=2010), History → Humanities (since=1990)
        insertConnection("courses", "belongs_to", "departments", mathId, scienceDeptId, mapOf("since" to 2000))
        insertConnection("courses", "belongs_to", "departments", physicsId, scienceDeptId, mapOf("since" to 2010))
        insertConnection("courses", "belongs_to", "departments", historyId, humanitiesDeptId, mapOf("since" to 1990))
    }

    private fun insertConnection(
        col1: String, connName: String, col2: String,
        id1: UUID, id2: UUID,
        fields: Map<String, Any>
    ) {
        val tableName = "ps_con_${col1}__${connName}__${col2}"
        val fk1 = "ps_cfk_$col1"
        val fk2 = "ps_cfk_$col2"
        val fieldCols = fields.keys.joinToString { "\"$it\"" }
        val fieldPlaceholders = fields.values.joinToString { "?" }
        val sql =
            "INSERT INTO \"$tableName\" (\"$fk1\", \"$fk2\", $fieldCols) VALUES ('$id1', '$id2', $fieldPlaceholders)"
        connection!!.prepareStatement(sql).use { stmt ->
            fields.values.forEachIndexed { i, v ->
                when (v) {
                    is Int -> stmt.setInt(i + 1, v)
                    is String -> stmt.setString(i + 1, v)
                    else -> stmt.setObject(i + 1, v)
                }
            }
            stmt.execute()
        }
    }

    private fun cleanupTables() {
        connection?.createStatement()?.use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS \"ps_con_students__attends__courses\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_con_courses__belongs_to__departments\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_enrollments\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_courses\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_departments\"")
            stmt.execute("DROP TABLE IF EXISTS \"ps_col_students\"")
            stmt.execute("DELETE FROM \"ps_config_connections\"")
            stmt.execute("DELETE FROM \"ps_config_collections\"")
        }
    }

    // ── Tests: simple collection queries ─────────────────────────────────────

    @Test
    fun `take all students returns all three`() {
        val result = driver!!.get(get { collection("students") })
        assertEquals(3, result.data.size)
        val names = result.data.map { it["students.name"] }.toSet()
        assertEquals(setOf("Alice", "Bob", "Carol"), names)
    }

    /*@Test
    fun `count all students returns 3`() {
        val result = driver!!.count(
            query { collection("students") },
            PolyTerminal.Count
        )
        assertEquals(3, result.count)
    }*/

    @Test
    fun `take students with gpa greater than 2 returns Alice and Bob`() {
        val result = driver!!.get(get { collection("students", "gpa" gt 2) })
        assertEquals(2, result.data.size)
        val names = result.data.map { it["students.name"] }.toSet()
        assertEquals(setOf("Alice", "Bob"), names)
    }

    @Test
    fun `take students with gpa less than 4 returns Bob and Carol`() {
        val result = driver!!.get(get { collection("students", "gpa" lt 4) })
        assertEquals(2, result.data.size)
        val names = result.data.map { it["students.name"] }.toSet()
        assertEquals(setOf("Bob", "Carol"), names)
    }

    @Test
    fun `take students with compound AND condition returns only Bob`() {
        // gpa > 2 AND gpa < 4  →  only Bob (gpa=3)
        val result = driver!!.get(get { collection("students", ("gpa" gt 2) and ("gpa" lt 4)) })
        assertEquals(1, result.data.size)
        assertEquals("Bob", result.data[0]["students.name"])
        assertEquals(3, result.data[0]["students.gpa"])
    }

    @Test
    fun `take students with compound OR condition returns Alice and Carol`() {
        // gpa == 4 OR gpa == 2  →  Alice and Carol
        val result = driver!!.get(get { collection("students", ("gpa" eq 4) or ("gpa" eq 2)) })
        assertEquals(2, result.data.size)
        val names = result.data.map { it["students.name"] }.toSet()
        assertEquals(setOf("Alice", "Carol"), names)
    }

    @Test
    fun `take students with condition matching nobody returns empty`() {
        val result = driver!!.get(get { collection("students", "gpa" gt 100) })
        assertTrue(result.data.isEmpty())
    }

    /* @Test
     fun `count with condition returns correct subset count`() {
         val path = query { collection("students", "gpa" gt 2) }
         assertEquals(2, driver!!.count(path, PolyTerminal.Count).count)
     }*/

    // ── Tests: single-hop connection queries ─────────────────────────────────

    @Test
    fun `take students with their attended courses returns correct row count`() {
        // 4 connection rows total: Alice-Math, Alice-History, Bob-Math, Carol-Physics
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses")
        })
        assertEquals(4, result.data.size)
    }

    @Test
    fun `join result contains correct fields from all three segments`() {
        val result = driver!!.get(get {
            collection("students", "name" eq "Alice")
            connection("attends", "courses", connectionCondition = "score" eq 95)
        })

        assertEquals(1, result.data.size)
        val row = result.data[0]

        // Student fields
        assertEquals("Alice", row["students.name"])
        assertEquals(4, row["students.gpa"])

        // Connection fields
        assertEquals(95, row["attends.score"])

        // Course fields
        assertEquals("Math", row["courses.title"])
        assertEquals(4, row["courses.credits"])
    }

    @Test
    fun `filter on collection side of join returns only matching student rows`() {
        // Only Alice's rows; she has 2 courses
        val result = driver!!.get(get {
            collection("students", "name" eq "Alice")
            connection("attends", "courses")
        })
        assertEquals(2, result.data.size)
        result.data.forEach { assertEquals("Alice", it["students.name"]) }
    }

    @Test
    fun `filter on connection data returns only high-scoring rows`() {
        // score > 70 → Alice-Math(95), Alice-History(80), Carol-Physics(75); excludes Bob-Math(60)
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses", connectionCondition = "score" gt 70)
        })
        assertEquals(3, result.data.size)
        val scores = result.data.map { it["attends.score"] as Int }
        assertTrue(scores.all { it > 70 })
    }

    @Test
    fun `filter on both collection and connection returns single precise row`() {
        // Bob AND score < 70 → Bob-Math(60)
        val result = driver!!.get(get {
            collection("students", "name" eq "Bob")
            connection("attends", "courses", connectionCondition = "score" lt 70)
        })
        assertEquals(1, result.data.size)
        assertEquals("Bob", result.data[0]["students.name"])
        assertEquals(60, result.data[0]["attends.score"])
        assertEquals("Math", result.data[0]["courses.title"])
    }

    @Test
    fun `filter on target collection side of join`() {
        // Only courses with credits == 4 (Math, Physics); History is excluded
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses", collectionCondition = "credits" eq 4)
        })
        // Rows: Alice-Math, Bob-Math, Carol-Physics → 3 rows
        assertEquals(3, result.data.size)
        val titles = result.data.map { it["courses.title"] }.toSet()
        assertEquals(setOf("Math", "Physics"), titles)
    }

    // ── Tests: two-hop connection queries ────────────────────────────────────

    @Test
    fun `two-hop join students - courses - departments returns correct row count`() {
        // Alice: Math→Science, History→Humanities → 2 rows
        // Bob:   Math→Science                     → 1 row
        // Carol: Physics→Science                  → 1 row
        // total: 4 rows
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses")
            connection("belongs_to", "departments")
        })
        assertEquals(4, result.data.size)
    }

    @Test
    fun `two-hop join row contains fields from all five segments`() {
        // Alice attends Math (score=95), Math belongs_to Science (since=2000)
        val result = driver!!.get(get {
            collection("students", "name" eq "Alice")
            connection("attends", "courses", connectionCondition = "score" eq 95)
            connection("belongs_to", "departments")
        })
        assertEquals(1, result.data.size)
        val row = result.data[0]

        assertEquals("Alice", row["students.name"])
        assertEquals(95, row["attends.score"])
        assertEquals("Math", row["courses.title"])
        assertEquals(2000, row["belongs_to.since"])
        assertEquals("Science", row["departments.name"])
    }

    @Test
    fun `two-hop filter on middle collection narrows result correctly`() {
        // Only courses with credits == 4 (Math, Physics) appear in the middle
        // Math→Science, Physics→Science → rows involving History are excluded
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses", collectionCondition = "credits" eq 4)
            connection("belongs_to", "departments")
        })
        // Alice-Math-Science, Bob-Math-Science, Carol-Physics-Science → 3 rows
        assertEquals(3, result.data.size)
        result.data.forEach {
            assertEquals("Science", it["departments.name"])
        }
    }

    @Test
    fun `two-hop filter on final department filters end of chain`() {
        // Only rows ending at Humanities → only Alice-History-Humanities
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses")
            connection("belongs_to", "departments", collectionCondition = "name" eq "Humanities")
        })
        assertEquals(1, result.data.size)
        assertEquals("Alice", result.data[0]["students.name"])
        assertEquals("History", result.data[0]["courses.title"])
        assertEquals("Humanities", result.data[0]["departments.name"])
    }

    @Test
    fun `two-hop compound condition across all hops returns exact single row`() {
        // Alice AND score > 90 AND Science dept → Alice-Math(95)-Science
        val result = driver!!.get(get {
            collection("students", "name" eq "Alice")
            connection("attends", "courses", connectionCondition = "score" gt 90)
            connection("belongs_to", "departments", collectionCondition = "name" eq "Science")
        })
        assertEquals(1, result.data.size)
        assertEquals("Alice", result.data[0]["students.name"])
        assertEquals(95, result.data[0]["attends.score"])
        assertEquals("Math", result.data[0]["courses.title"])
        assertEquals("Science", result.data[0]["departments.name"])
    }

    // ── Tests: structural correctness ─────────────────────────────────────────

    @Test
    fun `each result row has a unique combination of ids across hops`() {
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses")
            connection("belongs_to", "departments")
        })

        val uniqueKeys = result.data.map { row ->
            Triple(
                row["students._id"],
                row["courses._id"],
                row["departments._id"]
            )
        }.toSet()

        // No duplicate (student, course, department) combinations
        assertEquals(result.data.size, uniqueKeys.size)
    }

    @Test
    fun `result rows carry no cross-contamination between student fields`() {
        // Verify that Bob's row does not accidentally contain Alice's gpa
        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses")
        })

        val bobRows = result.data.filter { it["students.name"] == "Bob" }
        assertTrue(bobRows.isNotEmpty())
        bobRows.forEach { row ->
            assertEquals(
                3, row["students.gpa"],
                "Bob's row should carry Bob's gpa=3, not another student's"
            )
        }
    }

    @Test
    fun `student with no connections does not appear in join result`() {
        // Insert a student with no attends connections
        val lonelyId = UUID.randomUUID()
        driver!!.insertDocument(
            studentsModel,
            lonelyId, mapOf("name" to "Lonely", "gpa" to 1)
        )

        val result = driver!!.get(get {
            collection("students")
            connection("attends", "courses")
        })

        val names = result.data.map { it["ps_col_students__name"] }
        assertFalse(
            names.contains("Lonely"),
            "A student with no connections should not appear in a join result"
        )
    }

    @Test
    fun `collection-only query is unaffected by presence of connection data`() {
        // A plain students query should return all students regardless of whether they have connections
        val result = driver!!.get(get { collection("students") })
        assertEquals(3, result.data.size)
    }
}