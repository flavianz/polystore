package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.CollectionRef
import ch.flavianz.data.PolyDocument
import ch.flavianz.data.PolyValue
import ch.flavianz.model.CollectionConnection
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.DataType
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyQuery
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.sql.Connection
import java.util.LinkedList
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
        sql.append(" (").append(quoteIdentifier("ps_pk")).append(" UUID PRIMARY KEY")

        if(!createCollectionInstruction.parentCollection.isRoot()) {
            val parentCollectionName = createCollectionInstruction.parentCollection.toPostgresPath()
            // ps_pfk = polystore_parentforeignkey
            sql.append(", ").append(quoteIdentifier("ps_parent_fk")).append(" UUID CONSTRAINT ")
                .append(quoteIdentifier("${collectionName}_parent_${parentCollectionName}_fk"))
                .append(" references ").append(quoteIdentifier("ps_col_${parentCollectionName}"))
                .append(" (").append(quoteIdentifier("ps_pk")).append(")")
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
            .append(" (").append(quoteIdentifier("ps_pk")).append(")")
        sql.append(", ").append(quoteIdentifier("ps_cfk_$collection2Name")).append(" UUID CONSTRAINT ")
            .append(quoteIdentifier("${connection.name}_con_${collection2Name}_fk"))
            .append(" references ").append(quoteIdentifier("ps_col_${collection2Name}"))
            .append(" (").append(quoteIdentifier("ps_pk")).append(")")


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
        sql.append(quoteIdentifier("ps_pk"))
        if(collectionRef.path.size > 1) {
            sql.append(", ").append(quoteIdentifier("ps_parent_fk"))
        }

        val entries = ArrayList(insertObjectInstruction.data.fields.entries)

        for(entry in entries) {
            sql.append(", ").append(quoteIdentifier("f_${entry.key}"))
        }
        sql.append(") VALUES (").append(prepareValue(PolyValue.of(uuid)))

        if(collectionRef.path.size > 1) {
            sql.append(", ").append(prepareValue(PolyValue.of(insertObjectInstruction.collectionPathRef.parentDoc().uuid)))
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

        sql.append(" WHERE ").append(quoteIdentifier("ps_pk")).append(" = ").append(prepareValue(
            PolyValue.of(updateObjectInstruction.documentPathRef.uuid)))

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun take(query: PolyQuery, terminal: PolyTerminal.Take): PolyResult.Documents {
        val sql = StringBuilder()

        val selectClauses = terminal.fields.flatMap { fieldRef ->
            val node = query.path.first { (it.alias ?: it.collection) == fieldRef.alias }
            val col = CollectionRef(node.collection)
            val pgTable = "ps_col_${col.toPostgresPath()}"
            val tableAlias = node.alias ?: pgTable
            when (fieldRef) {
                is FieldRef.Wildcard -> {
                    val schema = DatabaseManager.getCollectionModel(col).schema
                    val pkCol = "${quoteIdentifier(tableAlias)}.${quoteIdentifier("ps_pk")} AS ${quoteIdentifier("${fieldRef.alias}__id")}"
                    val fieldCols = schema.fields.keys.map { f ->
                        "${quoteIdentifier(tableAlias)}.${quoteIdentifier("f_$f")} AS ${quoteIdentifier("${fieldRef.alias}__$f")}"
                    }
                    listOf(pkCol) + fieldCols
                }
                is FieldRef.Named -> listOf(
                    "${quoteIdentifier(tableAlias)}.${quoteIdentifier("f_${fieldRef.field}")} AS ${quoteIdentifier("${fieldRef.alias}__${fieldRef.field}")}"
                )
            }
        }

        sql.append("SELECT ").append(selectClauses.joinToString(", "))
        appendFromAndJoins(sql, query)
        appendWhere(sql, query)

        println(sql)

        val rs = connection.prepareStatement(sql.toString()).executeQuery()
        val targetNode = query.path.last()
        val targetCol = CollectionRef(LinkedList(query.path.map { it.collection }))
        val targetAlias = targetNode.alias ?: "ps_col_${targetCol.toPostgresPath()}"
        val schema = DatabaseManager.getCollectionModel(targetCol).schema

        return PolyResult.Documents(buildList {
            while (rs.next()) {
                val fields = schema.fields.entries.associate { (name, dataType) ->
                    name to when (dataType) {
                        DataType.STRING -> PolyValue.of(rs.getString("${targetAlias}__$name"))
                        DataType.INT    -> PolyValue.of(rs.getInt("${targetAlias}__$name"))
                        DataType.UUID   -> PolyValue.of(rs.getObject("${targetAlias}__$name") as UUID)
                        DataType.NULL   -> PolyValue.NullValue
                    }
                }
                add(PolyDocument(fields))
            }
        })
    }

    override fun count(query: PolyQuery, terminal: PolyTerminal.Count): PolyResult.Count {
        val sql = StringBuilder()
        sql.append("SELECT COUNT(*) AS ps_count")
        appendFromAndJoins(sql, query)
        appendWhere(sql, query)

        val rs = connection.prepareStatement(sql.toString()).executeQuery()
        rs.next()
        return PolyResult.Count(rs.getInt("ps_count"))
    }

    private fun appendFromAndJoins(sql: StringBuilder, query: PolyQuery) {
        val firstNode = query.path.first()
        val firstCol = CollectionRef(firstNode.collection)
        val firstTable = "ps_col_${firstCol.toPostgresPath()}"
        val firstAlias = firstNode.alias ?: firstTable
        sql.append(" FROM ").append(quoteIdentifier(firstTable))
            .append(" AS ").append(quoteIdentifier(firstAlias))

        for (i in 1 until query.path.size) {
            val prevNode = query.path[i - 1]
            val currNode = query.path[i]
            val currCol = CollectionRef(LinkedList(query.path.map { it.collection }))
            val prevCol = currCol.parent()
            val prevTable = "ps_col_${prevCol.toPostgresPath()}"
            val currTable = "ps_col_${currCol.toPostgresPath()}"
            val prevAlias = prevNode.alias ?: prevTable
            val currAlias = currNode.alias ?: currTable

            sql.append(" JOIN ").append(quoteIdentifier(currTable))
                .append(" AS ").append(quoteIdentifier(currAlias))
                .append(" ON ")
                .append(quoteIdentifier(currAlias)).append(".").append(quoteIdentifier("ps_parent_fk"))
                .append(" = ")
                .append(quoteIdentifier(prevAlias)).append(".").append(quoteIdentifier("ps_pk"))
        }
    }

    private fun appendWhere(sql: StringBuilder, query: PolyQuery) {
        val conditions = query.path.mapNotNull { node ->
            node.condition?.let {
                val col = CollectionRef(node.collection)
                val tableAlias = node.alias ?: "ps_col_${col.toPostgresPath()}"
                translateCondition(it, tableAlias)
            }
        }
        if (conditions.isNotEmpty()) {
            sql.append(" WHERE ").append(conditions.joinToString(" AND "))
        }
    }

    private fun translateCondition(condition: Condition, tableAlias: String): String {
        return when (condition) {
            is Condition.Equals      -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("f_${condition.field}")} = ${prepareValue(condition.value)}"
            is Condition.GreaterThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("f_${condition.field}")} > ${prepareValue(condition.value)}"
            is Condition.LessThan    -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("f_${condition.field}")} < ${prepareValue(condition.value)}"
            is Condition.And -> "(${translateCondition(condition.left, tableAlias)} AND ${translateCondition(condition.right, tableAlias)})"
            is Condition.Or  -> "(${translateCondition(condition.left, tableAlias)} OR ${translateCondition(condition.right, tableAlias)})"
            is Condition.Not -> "NOT (${translateCondition(condition.condition, tableAlias)})"
        }
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_][a-zA-Z0-9_]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }

    private fun prepareValue(value: PolyValue): String {
        return when (value) {
            is PolyValue.StringValue, is PolyValue.UUIDValue -> {
                "'${value.value.toString()}'"
            }
            is PolyValue.IntValue -> {
                value.value.toString()
            }
            is PolyValue.NullValue -> {
                "null"
            }
        }
    }
}

