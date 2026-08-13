package ch.flavianz.connection

import connection.ConnectionManager
import connection.DatabaseConnection
import connection.PostgresConnection
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationHandler
import java.sql.Connection
import java.sql.Statement
import kotlin.test.*

class PostgresConnectionTests {

    private fun mockConnection(
        isClosedValue: Boolean = false,
        createStatementHandler: (() -> Statement)? = null
    ): Connection {
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "isClosed" -> isClosedValue
                    "close" -> null
                    "createStatement" -> createStatementHandler?.invoke() ?: mockStatement()
                    else -> null
                }
            }
        ) as Connection
    }

    private fun mockStatement(executeHandler: ((String) -> Boolean)? = null): Statement {
        return Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execute" -> executeHandler?.invoke(args[0] as String) ?: true
                    else -> null
                }
            }
        ) as Statement
    }

    @Test
    fun testPostgresConnectionProperties() {
        val conn = PostgresConnection(
            host = "localhost",
            port = 5432,
            database = "polystore_test",
            username = "postgres",
            password = "password"
        )
        assertEquals("PostgreSQL[polystore_test]", conn.name)
        assertFalse(conn.isConnected)
        assertFailsWith<IllegalStateException> {
            conn.jdbcConnection
        }
    }

    class FakeDatabaseConnection(
        override val name: String,
        override var isConnected: Boolean = false,
        var pingResult: Boolean = true
    ) : DatabaseConnection {
        var connectCalled = 0
        var disconnectCalled = 0

        override fun connect() {
            isConnected = true
            connectCalled++
        }

        override fun disconnect() {
            isConnected = false
            disconnectCalled++
        }

        override fun ping(): Boolean = pingResult
    }

    @Test
    fun testConnectionManagerLifecycle() {
        val manager = ConnectionManager()
        val mockConn1 = FakeDatabaseConnection("MockConn1")
        val mockConn2 = FakeDatabaseConnection("MockConn2")

        manager.register(mockConn1)
        manager.register(mockConn2)

        // Retrieve check
        assertEquals(mockConn1, manager.get<DatabaseConnection>("MockConn1"))

        // Duplicate registration check
        assertFailsWith<IllegalArgumentException> {
            manager.register(mockConn1)
        }

        // Connect/Disconnect check
        manager.connectAll()
        assertTrue(mockConn1.isConnected)
        assertEquals(1, mockConn1.connectCalled)
        assertTrue(mockConn2.isConnected)
        assertEquals(1, mockConn2.connectCalled)

        manager.disconnectAll()
        assertFalse(mockConn1.isConnected)
        assertEquals(1, mockConn1.disconnectCalled)
        assertFalse(mockConn2.isConnected)
        assertEquals(1, mockConn2.disconnectCalled)
    }

    @Test
    fun testConnectionManagerHealthAndStatus() {
        val manager = ConnectionManager()
        val mockConn1 = FakeDatabaseConnection("MockConn1", isConnected = true, pingResult = true)
        val mockConn2 = FakeDatabaseConnection("MockConn2", isConnected = false, pingResult = false)

        manager.register(mockConn1)
        manager.register(mockConn2)

        val status = manager.status()
        assertEquals(status["MockConn1"], true)
        assertEquals(status["MockConn2"], false)

        val health = manager.healthCheck()
        assertEquals(health["MockConn1"], true)
        assertEquals(health["MockConn2"], false)
    }
}
