package ch.flavianz.core.driver

import ch.flavianz.core.model.CollectionModel
import core.driver.DatabaseDriver
import java.sql.Connection

class PostgresDriver(val connection: Connection) : DatabaseDriver {

    override fun createCollection(collection: CollectionModel) {
        val sql = StringBuilder()

        // Add the primary key column
        sql.append("CREATE TABLE ").append(quoteIdentifier(collection.name))
        sql.append("(").append(quoteIdentifier("ps_col_${collection.name}")).append(" UUID PRIMARY KEY")

        // Add fields from the schema
        for ((name, dataType) in collection.schema.fields) {
            sql.append(", ").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        println(sql)

        connection.prepareStatement(sql.toString()).execute()
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_][a-zA-Z0-9_]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }
}

