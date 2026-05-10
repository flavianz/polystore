package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.DataObject
import ch.flavianz.model.CollectionConnection
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.query.Collector
import ch.flavianz.query.Query
import java.security.InvalidParameterException
import java.sql.Connection
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

class PostgresDriver(val connection: Connection) : DatabaseDriver {
    override fun createCollection(createCollectionInstruction: CreateCollectionInstruction) {
        val collectionModel = createCollectionInstruction.collectionModel
        val collectionName = createCollectionInstruction.parentCollection.sub(collectionModel.name).toPostgresPath()

        val sql = StringBuilder()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_col_$collectionName"))

        // ps_pk = polystore_primarykey
        sql.append(" (").append(quoteIdentifier("ps_pk_$collectionName")).append(" UUID PRIMARY KEY")

        if(!createCollectionInstruction.parentCollection.isRoot()) {
            val parentCollectionName = createCollectionInstruction.parentCollection.toPostgresPath()
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

        connection.prepareStatement(sql.toString()).execute()

        // TODO: move this out of specific driver
        DatabaseManager.registerCollection(createCollectionInstruction.collectionModel, createCollectionInstruction.parentCollection)

        if(collectionModel.subCollections.isNotEmpty()) {
            for (model in ArrayList(collectionModel.subCollections.values)) {
                createCollection(CreateCollectionInstruction(
                    createCollectionInstruction.parentCollection.sub(createCollectionInstruction.collectionModel.name), model))
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

    override fun insertObject(uuid: UUID, insertObjectInstruction: InsertObjectInstruction) {
        val sql = StringBuilder()
        val collectionRef = insertObjectInstruction.collectionPathRef.toCollectionRef()
        sql.append("INSERT INTO ").append(quoteIdentifier("ps_col_${collectionRef.toPostgresPath()}")).append(" (")
        sql.append(quoteIdentifier("ps_pk_${collectionRef.toPostgresPath()}"))
        if(collectionRef.path.size > 1) {
            sql.append(", ").append(quoteIdentifier("ps_pfk_${collectionRef.parent().toPostgresPath()}"))
        }

        val entries = ArrayList(insertObjectInstruction.data.fields.entries)

        for(entry in entries) {
            sql.append(", ").append(quoteIdentifier("f_${entry.key}"))
        }
        sql.append(") VALUES (").append(prepareValue(uuid))

        if(collectionRef.path.size > 1) {
            sql.append(", ").append(prepareValue(insertObjectInstruction.collectionPathRef.parentDoc().uuid))
        }

        for(entry in entries) {
            sql.append(", ").append(prepareValue(entry.value))
        }
        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun updateObject(updateObjectInstruction: UpdateObjectInstruction) {
        val sql = StringBuilder()
        val collectionRef = updateObjectInstruction.documentPathRef.parentCollection().toCollectionRef()
        sql.append("UPDATE ").append(quoteIdentifier("ps_col_${collectionRef.toPostgresPath()}")).append(" SET ")

        for(entry in updateObjectInstruction.data.fields) {
            sql.append(quoteIdentifier("f_${entry.key}")).append(" = ").append(prepareValue(entry.value)).append(", ")
        }
        if(updateObjectInstruction.data.fields.isNotEmpty()) {
            // remove last comma
            sql.deleteRange(sql.length - 2, sql.length)
        }

        sql.append(" WHERE ").append(quoteIdentifier("ps_pk_${collectionRef.toPostgresPath()}")).append(" = ").append(prepareValue(updateObjectInstruction.documentPathRef.uuid))

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun query(query: Query): List<DataObject> {
        val sql = StringBuilder()
        sql.append("SELECT ")
        when(query.collector) {
            is Collector.TakeCollector -> {
                for(collectionReference in query.collector.properties) {
                    for(field in collectionReference.value) {
                        sql.append(quoteIdentifier(collectionReference.key)).append(".").append(quoteIdentifier(field)).append(", ")
                    }
                }
                if(query.collector.properties.isNotEmpty()) {
                    // remove last comma
                    sql.deleteRange(sql.length - 2, sql.length)
                }
            }
            is Collector.CollectCollector -> {
                sql.append(quoteIdentifier(query.collector.propertyName)).append(".*")
            }
        }
        sql.append(" FROM ")
        return emptyList()
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

