package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.CollectionConnection
import ch.flavianz.query.CreateCollectionQuery
import ch.flavianz.query.InsertRootObjectQuery
import java.security.InvalidParameterException
import java.sql.Connection
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

class PostgresDriver(val connection: Connection) : DatabaseDriver {
    override fun createCollection(createCollectionQuery: CreateCollectionQuery) {
        val collectionModel = createCollectionQuery.collectionModel
        val collectionName = createCollectionQuery.parentCollection.sub(collectionModel.name).toPostgresPath()
        println("parent ${createCollectionQuery.parentCollection}")

        val sql = StringBuilder()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_col_$collectionName"))

        // ps_pk = polystore_primarykey
        sql.append(" (").append(quoteIdentifier("ps_pk_$collectionName")).append(" UUID PRIMARY KEY")

        if(!createCollectionQuery.parentCollection.isRoot()) {
            val parentCollectionName = createCollectionQuery.parentCollection.toPostgresPath()
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

        // TODO: move this out of specific driver
        DatabaseManager.registerCollection(createCollectionQuery.collectionModel, createCollectionQuery.parentCollection)

        if(collectionModel.subCollections.isNotEmpty()) {
            for (model in ArrayList(collectionModel.subCollections.values)) {
                createCollection(CreateCollectionQuery(
                    createCollectionQuery.parentCollection.sub(createCollectionQuery.collectionModel.name), model))
            }
        }
    }

    override fun createConnection(connection: CollectionConnection) {
        val sql = StringBuilder()
        val collection1Name = connection.collection1.toPostgresPath()
        val collection2Name = connection.collection2.toPostgresPath()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_con_${collection1Name}__${connection.name}__${collection2Name}"))

        // ps_cfk = polystore_connectionforeignkey
        sql.append(" (").append(quoteIdentifier("ps_cfk_$collection1Name")).append(" UUID CONSTRAINT ")
            .append(quoteIdentifier("${connection.name}_con_${collection1Name}_fk"))
            .append(" references ").append(quoteIdentifier("ps_col_${collection1Name}"))
            .append(" (").append(quoteIdentifier("ps_pk_${collection1Name}")).append(")")
        sql.append(", ").append(quoteIdentifier("ps_cfk_$collection2Name")).append(" UUID CONSTRAINT ")
            .append(quoteIdentifier("${connection.name}_con_${collection2Name}_fk"))
            .append(" references ").append(quoteIdentifier("ps_col_${collection2Name}"))
            .append(" (").append(quoteIdentifier("ps_pk_${collection2Name}")).append(")")


        // Add fields from the schema
        for ((name, dataType) in connection.connectionData.fields) {
            // cd = connectiondata
            sql.append(", cd_").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun insertRootObject(uuid: UUID, insertRootObjectQuery: InsertRootObjectQuery) {
        val sql = StringBuilder()
        sql.append("INSERT INTO ").append(quoteIdentifier("ps_col_${insertRootObjectQuery.collection.toPostgresPath()}")).append(" (")
        sql.append(quoteIdentifier("ps_pk_${insertRootObjectQuery.collection.toPostgresPath()}"))

        val entries = ArrayList(insertRootObjectQuery.data.fields.entries)

        for(entry in entries) {
            sql.append(", ").append(quoteIdentifier("f_${entry.key}"))
        }
        sql.append(") VALUES (").append(prepareValue(uuid))
        for(entry in entries) {
            sql.append(", ").append(prepareValue(entry.value))
        }
        sql.append(")")

        println(sql)

        this.connection.prepareStatement(sql.toString()).execute()
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_][a-zA-Z0-9_]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }

    private fun prepareValue(value: Any): String {
        return when (value) {
            is String, is UUID -> {
                "'${value}'"
            }
            is Int -> {
                value.toString()
            }
            else -> {
                throw InvalidParameterException("Unknown value type ${value.javaClass}")
            }
        }
    }
}

