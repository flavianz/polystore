package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.query.CreateQuery
import java.sql.Connection
import kotlin.collections.iterator

class PostgresDriver(val connection: Connection) : DatabaseDriver {
    override fun createCollection(createQuery: CreateQuery) {
        val collectionModel = createQuery.collectionModel
        val collectionName = createQuery.parentCollection.sub(collectionModel.name).toPostgresPath()
        println("parent ${createQuery.parentCollection}")

        val sql = StringBuilder()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_col_$collectionName"))

        // ps_pk = polystore_primarykey
        sql.append(" (").append(quoteIdentifier("ps_pk_$collectionName")).append(" UUID PRIMARY KEY")

        if(!createQuery.parentCollection.isRoot()) {
            val parentCollectionName = createQuery.parentCollection.toPostgresPath()
            // ps_pfk = polystore_parentforeignkey
            sql.append(", ").append(quoteIdentifier("ps_pfk_$parentCollectionName")).append(" UUID CONSTRAINT ")
                .append(quoteIdentifier("${collectionName}_parent_${parentCollectionName}_fk"))
                .append(" references ").append(quoteIdentifier("ps_col_${parentCollectionName}"))
                .append(" (").append(quoteIdentifier("ps_pk_${parentCollectionName}")).append(")")
        }

        // Add fields from the schema
        for ((name, dataType) in collectionModel.schema.fields) {
            sql.append(", f_").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        println(sql)
        connection.prepareStatement(sql.toString()).execute()

        DatabaseManager.registerCollection(createQuery.collectionModel, createQuery.parentCollection)

        if(collectionModel.subCollections.isNotEmpty()) {
            for (model in HashMap(collectionModel.subCollections).values) {
                createCollection(CreateQuery(
                    model, createQuery.parentCollection.sub(createQuery.collectionModel.name)))
            }
        }
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_][a-zA-Z0-9_]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }
}

