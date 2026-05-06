package ch.flavianz.core.driver

import ch.flavianz.core.model.CollectionModel
import core.driver.DatabaseDriver
import java.sql.Connection

class PostgresDriver(val connection: Connection) : DatabaseDriver {

    override fun createCollection(collectionModel: CollectionModel) {
        createCollection(collectionModel, null)
    }

    private fun createCollection(collectionModel: CollectionModel, parentCollectionName: String? = null) {
        val sql = StringBuilder()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_col_${collectionModel.name}"))

        // ps_pk = polystore_primarykey
        sql.append(" (").append(quoteIdentifier("ps_pk_${collectionModel.name}")).append(" UUID PRIMARY KEY")

        if(parentCollectionName != null) {
            // ps_pfk = polystore_parentforeignkey
            sql.append(", ").append(quoteIdentifier("ps_pfk_$parentCollectionName")).append(" UUID CONSTRAINT ")
                .append(quoteIdentifier("${collectionModel.name}_parent_${parentCollectionName}_fk"))
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

        if(collectionModel.subCollections.isNotEmpty()) {
            for (model in collectionModel.subCollections) {
                createCollection(CollectionModel("${collectionModel.name}_${model.name}", model.schema, model.subCollections), collectionModel.name)
            }
        }
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_][a-zA-Z0-9_]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }
}

