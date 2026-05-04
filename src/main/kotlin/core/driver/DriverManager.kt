package ch.flavianz.core.driver

import core.driver.DatabaseDriver

class DriverManager private constructor(){
    var postgresDriver: PostgresDriver? = null

    fun execute(a: DatabaseDriver.() -> Unit) {
        postgresDriver?.a()
    }

    fun initPostgres(jdbcConnection: java.sql.Connection): DriverManager {
        this.postgresDriver = PostgresDriver(jdbcConnection)
        return this
    }



    companion object {
        @Volatile
        private var instance: DriverManager? = null

        fun initialize(block: DriverManager.() -> Unit): DriverManager {
            check(instance == null) { "DriverManager is already initialized" }
            return synchronized(this) {
                check(instance == null) { "DriverManager is already initialized" }
                DriverManager().apply(block).also { instance = it }
            }
        }

        fun getInstance(): DriverManager =
            checkNotNull(instance) { "DriverManager is not initialized. Call initialize() first." }
    }
}