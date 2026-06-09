package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.CreateCollectionInstruction
import ch.flavianz.instructions.InsertObjectInstruction
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import java.sql.Connection
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

class PostgresDriver(val connection: Connection) : DatabaseDriver {
    override fun createCollection(instruction: CreateCollectionInstruction) {
        val collectionModel = instruction.collectionModel
        val collectionName = collectionModel.name

        val sql = StringBuilder()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_col_$collectionName"))

        // ps_pk = polystore_primarykey
        sql.append(" (").append(quoteIdentifier("ps_pk")).append(" UUID PRIMARY KEY")

        if (instruction.parentCollectionName != null) {
            val parentCollectionName = instruction.parentCollectionName
            // ps_pfk = polystore_parentforeignkey
            sql.append(", ").append(quoteIdentifier("ps_parent_fk")).append(" UUID CONSTRAINT ")
                .append(quoteIdentifier("${collectionName}_parent_${parentCollectionName}_fk"))
                .append(" references ").append(quoteIdentifier("ps_col_${parentCollectionName}"))
                .append(" (").append(quoteIdentifier("ps_pk")).append(")")
        }

        // Add fields from the schema
        for ((name, dataType) in collectionModel.schema) {
            sql.append(", ps_f_").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        connection.prepareStatement(sql.toString()).execute()
    }

    override fun createConnection(connection: ConnectionModel) {
        val sql = StringBuilder()
        val collection1Name = connection.collection1Name
        val collection2Name = connection.collection2Name

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ")
            .append(quoteIdentifier("ps_con_${collection1Name}__${connection.name}__${collection2Name}"))

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
        for ((name, dataType) in connection.connectionDataSchema) {
            // f = field
            sql.append(", ps_f_").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun insertDocument(uuid: UUID, instruction: InsertObjectInstruction) {
        val sql = StringBuilder()
        val collectionRef = instruction.collectionPath.toCollectionRef()
        sql.append("INSERT INTO ").append(quoteIdentifier("ps_col_${collectionRef.leafName()}")).append(" (")
        sql.append(quoteIdentifier("ps_pk"))
        if (collectionRef.segments.size > 1) {
            sql.append(", ").append(quoteIdentifier("ps_parent_fk"))
        }

        val entries = ArrayList(instruction.data.entries)

        for (entry in entries) {
            sql.append(", ").append(quoteIdentifier("ps_f_${entry.key}"))
        }
        sql.append(") VALUES (").append(prepareValue(PolyValue.of(uuid)))

        if (collectionRef.segments.size > 1) {
            sql.append(", ").append(prepareValue(PolyValue.of(instruction.collectionPath.parentDoc().uuid)))
        }

        for (entry in entries) {
            sql.append(", ").append(prepareValue(entry.value))
        }
        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun updateDocument(instruction: UpdateObjectInstruction) {
        val sql = StringBuilder()
        val collectionRef = instruction.documentPath.parentCollection().toCollectionRef()
        sql.append("UPDATE ").append(quoteIdentifier("ps_col_${collectionRef.leafName()}")).append(" SET ")

        for (entry in instruction.data.entries) {
            sql.append(quoteIdentifier("ps_f_${entry.key}")).append(" = ").append(prepareValue(entry.value))
                .append(", ")
        }
        if (instruction.data.isNotEmpty()) {
            // remove last comma
            sql.deleteRange(sql.length - 2, sql.length)
        }

        sql.append(" WHERE ").append(quoteIdentifier("ps_pk")).append(" = ").append(
            prepareValue(
                PolyValue.of(instruction.documentPath.uuid)
            )
        )

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun insertConnection(
        connection: ConnectionModel,
        collection1Name: String,
        uuid1: UUID,
        collection2Name: String,
        uuid2: UUID,
        connectionData: PolyData
    ) {
        val sql = StringBuilder()
        val tableName =
            "ps_con_${connection.collection1Name}__${connection.name}__${connection.collection2Name}"
        sql.append("INSERT INTO ").append(quoteIdentifier(tableName)).append(" (")
        sql.append(quoteIdentifier("ps_cfk_${connection.collection1Name}")).append(", ")
        sql.append(quoteIdentifier("ps_cfk_${connection.collection2Name}"))

        for (entry in connectionData) {
            sql.append(", ").append(quoteIdentifier("ps_f_${entry.key}"))
        }
        sql.append(") VALUES (")

        sql.append(prepareValue(PolyValue.of(uuid1))).append(", ")
        sql.append(prepareValue(PolyValue.of(uuid2)))

        for (entry in connectionData) {
            sql.append(", ").append(prepareValue(entry.value))
        }
        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }


    override fun take(path: QueryPath, terminal: PolyTerminal.Take): List<PolyData> {
        val sql = StringBuilder()

        val selectClauses = terminal.fields.flatMap { fieldRef ->
            fun generateCollectionSelectClause(collectionName: String): List<String> {
                val pgTable = "ps_col_${collectionName}"
                return when (fieldRef) {
                    is FieldRef.Wildcard -> {
                        val schema = DatabaseManager.getCollectionModel(collectionName).schema
                        val pkCol =
                            "${quoteIdentifier(pgTable)}.${quoteIdentifier("ps_pk")} AS ${quoteIdentifier("${pgTable}__id")}"
                        val fieldCols = schema.keys.map { f ->
                            "${quoteIdentifier(pgTable)}.${quoteIdentifier("ps_f_$f")} AS ${quoteIdentifier("${pgTable}__$f")}"
                        }
                        listOf(pkCol) + fieldCols
                    }

                    is FieldRef.Named -> listOf(
                        "${quoteIdentifier(pgTable)}.${quoteIdentifier("ps_f_${fieldRef.field}")} AS ${
                            quoteIdentifier(
                                "${pgTable}__${fieldRef.field}"
                            )
                        }"
                    )
                }
            }

            val segmentIndex = path.segments.indexOfFirst {
                when (it) {
                    is QuerySegment.Collection -> it.name == fieldRef.segment
                    is QuerySegment.Connection -> it.connectionName == fieldRef.segment || it.collectionName == fieldRef.segment
                }
            }
            check(segmentIndex != -1) { "take field segment not found in query path ${path}" }
            when (val segment = path.segments[segmentIndex]) {
                is QuerySegment.Collection -> generateCollectionSelectClause(
                    segment.name
                )

                is QuerySegment.Connection -> {
                    if (segment.collectionName == fieldRef.segment) {
                        generateCollectionSelectClause(
                            segment.collectionName
                        )
                    } else {
                        val connectionModel = DatabaseManager.getConnectionModel(segment.connectionName)
                        val pgTable =
                            "ps_con_${connectionModel.collection1Name}__${connectionModel.name}__${connectionModel.collection2Name}"
                        when (fieldRef) {
                            is FieldRef.Wildcard -> {
                                val schema = connectionModel.connectionDataSchema
                                schema.keys.map { f ->
                                    "${quoteIdentifier(pgTable)}.${quoteIdentifier("ps_f_$f")} AS ${quoteIdentifier("${pgTable}__$f")}"
                                }
                            }

                            is FieldRef.Named -> listOf(
                                "${quoteIdentifier(pgTable)}.${quoteIdentifier("ps_f_${fieldRef.field}")} AS ${
                                    quoteIdentifier(
                                        "${pgTable}__${fieldRef.field}"
                                    )
                                }"
                            )
                        }
                    }
                }
            }
        }

        sql.append("SELECT ").append(selectClauses.joinToString(", "))
        appendFromAndJoins(sql, path)
        appendWhere(sql, path)

        val rs = connection.prepareStatement(sql.toString()).executeQuery()

        return buildList {
            val metaData = rs.metaData
            val columnNames = (1..metaData.columnCount).map { metaData.getColumnName(it) }

            while (rs.next()) {
                val fields = columnNames.associateWith { col ->
                    when (val obj = rs.getObject(col)) {
                        null -> PolyValue.NullValue
                        is String -> PolyValue.of(obj)
                        is Int -> PolyValue.of(obj)
                        is UUID -> PolyValue.of(obj)
                        else -> throw IllegalStateException("Unexpected type ${obj::class} for column $col")
                    }
                }
                add(fields)
            }
        }
    }

    override fun count(path: QueryPath, terminal: PolyTerminal.Count): PolyResult.Count {
        val sql = StringBuilder()
        sql.append("SELECT COUNT(*) AS ps_count")
        appendFromAndJoins(sql, path)
        appendWhere(sql, path)

        val rs = connection.prepareStatement(sql.toString()).executeQuery()
        rs.next()
        return PolyResult.Count(rs.getInt("ps_count"))
    }

    private fun appendFromAndJoins(sql: StringBuilder, path: QueryPath) {
        val firstNode = path.segments.first() as QuerySegment.Collection
        val firstCol = CollectionRef(firstNode.name)
        val firstTable = "ps_col_${firstCol.leafName()}"
        sql.append(" FROM ").append(quoteIdentifier(firstTable))

        for (i in 1 until path.segments.size) {
            when (val currentSegment = path.segments[i]) {
                is QuerySegment.Collection -> {
                    val prevCol = path.segments[i - 1].collectionName()
                    val currCol = currentSegment.collectionName()
                    val prevTable = "ps_col_${prevCol}"
                    val currTable = "ps_col_${currCol}"

                    sql.append(" JOIN ").append(quoteIdentifier(currTable))
                        .append(" ON ")
                        .append(quoteIdentifier(currTable)).append(".").append(quoteIdentifier("ps_parent_fk"))
                        .append(" = ")
                        .append(quoteIdentifier(prevTable)).append(".").append(quoteIdentifier("ps_pk"))
                }

                is QuerySegment.Connection -> {
                    val connectionModel = DatabaseManager.getConnectionModel(currentSegment.connectionName)
                    val prevCol = path.segments[i - 1].collectionName()
                    val prevTable = "ps_col_${prevCol}"
                    val nextTable = "ps_col_${connectionModel.collection2Name}"
                    val connTable =
                        "ps_con_${connectionModel.toPostgresPath()}"

                    sql.append(" JOIN ").append(quoteIdentifier(connTable))
                        .append(" ON ")
                        .append(quoteIdentifier(connTable)).append(".")
                        .append(quoteIdentifier("ps_cfk_${connectionModel.collection1Name}"))
                        .append(" = ")
                        .append(quoteIdentifier(prevTable)).append(".").append(quoteIdentifier("ps_pk"))
                        .append(" JOIN ").append(quoteIdentifier(nextTable))
                        .append(" ON ")
                        .append(quoteIdentifier(nextTable)).append(".")
                        .append(quoteIdentifier("ps_pk")).append(" = ")
                        .append(quoteIdentifier(connTable)).append(".")
                        .append("ps_cfk_${connectionModel.collection2Name}")
                }
            }
        }
    }

    private fun appendWhere(sql: StringBuilder, path: QueryPath) {
        var i = 0
        val conditions: List<String> = path.segments.flatMap { segment ->
            i++
            buildList {
                when (segment) {
                    is QuerySegment.Collection -> {
                        if (segment.condition != null) {
                            val col = path.segments[i - 1].collectionName()
                            val pgTable = "ps_col_${col}"
                            add(translateCondition(segment.condition, pgTable))
                        }
                    }

                    is QuerySegment.Connection -> {
                        if (segment.collectionCondition != null) {
                            val col = path.segments[i - 1].collectionName()
                            val pgTable = "ps_col_${col}"
                            add(translateCondition(segment.collectionCondition, pgTable))
                        }
                        if (segment.connectionCondition != null) {
                            val con = DatabaseManager.getConnectionModel(segment.connectionName)
                            val pgTable = "ps_con_${con.toPostgresPath()}"
                            add(translateCondition(segment.connectionCondition, pgTable))
                        }
                    }
                }
            }

        }
        if (conditions.isNotEmpty()) {
            sql.append(" WHERE ").append(conditions.joinToString(" AND "))
        }
    }

    private fun translateCondition(condition: Condition, tableAlias: String): String {
        return when (condition) {
            is Condition.Comparison.Equals -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("ps_f_${condition.field}")} = ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Comparison.GreaterThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("ps_f_${condition.field}")} > ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Comparison.LessThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("ps_f_${condition.field}")} < ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Logic.And -> "(${
                translateCondition(
                    condition.left,
                    tableAlias
                )
            } AND ${translateCondition(condition.right, tableAlias)})"

            is Condition.Logic.Or -> "(${
                translateCondition(
                    condition.left,
                    tableAlias
                )
            } OR ${translateCondition(condition.right, tableAlias)})"

            is Condition.Not -> "NOT (${translateCondition(condition.condition, tableAlias)})"

            is Condition.In -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("ps_f_${condition.field}")} IN (${
                condition.list.joinToString(",") { prepareValue(it) }
            })"
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

