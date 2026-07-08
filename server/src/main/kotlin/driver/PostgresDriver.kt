package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.CollectionRef
import ch.flavianz.model.DataType
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.model.toJson
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import ch.flavianz.server.FieldDefinition
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

@Suppress("SqlSourceToSinkFlow")
class PostgresDriver(val connection: Connection) : DatabaseDriver {
    override fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        val sql = StringBuilder()

        // Add the primary key column
        // ps_col = polystore_collection
        sql.append("CREATE TABLE ").append(quoteIdentifier("ps_col_$collectionName"))

        sql.append(" (").append(quoteIdentifier("_id")).append(" UUID PRIMARY KEY")

        if (parentCollectionName != null) {
            // ps_pfk = polystore_parentforeignkey
            sql.append(", ").append(quoteIdentifier("ps_parent_fk")).append(" UUID CONSTRAINT ")
                .append(quoteIdentifier("${collectionName}_parent_${parentCollectionName}_fk"))
                .append(" references ").append(quoteIdentifier("ps_col_${parentCollectionName}"))
                .append(" (").append(quoteIdentifier("_id")).append(")")
        }

        // Add fields from the schema
        for ((name, dataType) in schema) {
            sql.append(", ").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        connection.prepareStatement(sql.toString()).execute()

        registerCollection(collectionName, schema, parentCollectionName)
    }

    override fun dropCollection(collection: CollectionModel) {
        val sql = StringBuilder()
        fun appendDropCollectionsRecursive(collection: CollectionModel) {
            for(child in collection.childCollections) {
                appendDropCollectionsRecursive(DatabaseManager.getCollectionModel(child))
            }
            sql.append(tableDropStatement(collection.name))
        }
        appendDropCollectionsRecursive(collection)
        connection.prepareStatement(sql.toString()).execute()
        return
    }

    override fun dropConnection(connectionModel: ConnectionModel) {
        val sql = StringBuilder()
        sql.append("DROP TABLE ")
        sql.append(quoteIdentifier("ps_con_${connectionModel.collection1Name}__${connectionModel.name}__${connectionModel.collection2Name}"))
        sql.append("; DELETE FROM ps_config_connections WHERE name = ${prepareValue(PolyValue.of(connectionModel.name))};")
        connection.prepareStatement(sql.toString()).execute()
        return
    }

    private fun tableDropStatement(collectionName: String): String {
        val sql = StringBuilder()
        sql.append("DROP TABLE ")
        sql.append(quoteIdentifier("ps_col_${collectionName}"))
        sql.append(";")
        sql.append("DELETE FROM ps_config_collections WHERE name = ${prepareValue(PolyValue.of(collectionName))};")
        return sql.toString()
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
            .append(" (").append(quoteIdentifier("_id")).append(")")
        sql.append(", ").append(quoteIdentifier("ps_cfk_$collection2Name")).append(" UUID CONSTRAINT ")
            .append(quoteIdentifier("${connection.name}_con_${collection2Name}_fk"))
            .append(" references ").append(quoteIdentifier("ps_col_${collection2Name}"))
            .append(" (").append(quoteIdentifier("_id")).append(")")


        // Add fields from the schema
        for ((name, dataType) in connection.connectionDataSchema) {
            // f = field
            sql.append(", ").append(name).append(" ").append(dataType.toPostgresType())
        }

        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()

        registerConnection(
            connection.name,
            connection.collection1Name,
            connection.collection2Name,
            connection.connectionDataSchema
        )
    }

    override fun insertDocument(collection: CollectionModel, uuid: UUID, data: PolyData, parentDocUuid: UUID?) {
        val sql = StringBuilder()
        sql.append("INSERT INTO ").append(quoteIdentifier("ps_col_${collection.name}")).append(" (")
        sql.append(quoteIdentifier("_id"))

        if (parentDocUuid != null) {
            sql.append(", ").append(quoteIdentifier("ps_parent_fk"))
        }

        for (entry in data.entries) {
            sql.append(", ").append(quoteIdentifier("${entry.key}"))
        }
        sql.append(") VALUES (").append(prepareValue(PolyValue.of(uuid)))

        if (parentDocUuid != null) {
            sql.append(", ").append(prepareValue(PolyValue.of(parentDocUuid)))
        }

        for (entry in data.entries) {
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
            sql.append(quoteIdentifier("${entry.key}")).append(" = ").append(prepareValue(entry.value))
                .append(", ")
        }
        if (instruction.data.isNotEmpty()) {
            // remove last comma
            sql.deleteRange(sql.length - 2, sql.length)
        }

        sql.append(" WHERE ").append(quoteIdentifier("_id")).append(" = ").append(
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
            sql.append(", ").append(quoteIdentifier("${entry.key}"))
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
                            "${quoteIdentifier(pgTable)}.${quoteIdentifier("_id")} AS ${quoteIdentifier("${collectionName}._id")}"
                        val fieldCols = schema.keys.map { f ->
                            "${quoteIdentifier(pgTable)}.${quoteIdentifier("$f")} AS ${quoteIdentifier("${collectionName}.$f")}"
                        }
                        listOf(pkCol) + fieldCols
                    }

                    is FieldRef.Named -> listOf(
                        "${quoteIdentifier(pgTable)}.${quoteIdentifier("${fieldRef.field}")} AS ${
                            quoteIdentifier(
                                "${collectionName}.${fieldRef.field}"
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
            check(segmentIndex != -1) { "take field segment not found in query path $path" }
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
                                    "${quoteIdentifier(pgTable)}.${quoteIdentifier("$f")} AS ${quoteIdentifier("${connectionModel.name}.$f")}"
                                }
                            }

                            is FieldRef.Named -> listOf(
                                "${quoteIdentifier(pgTable)}.${quoteIdentifier("${fieldRef.field}")} AS ${
                                    quoteIdentifier(
                                        "${connectionModel.name}.${fieldRef.field}"
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

        println(sql.toString())
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

    override fun init() {
        connection.prepareStatement(
            """create table if not exists ps_config_collections
(
    name text not null primary key,
    fields jsonb not null,
    parent_collection text references ps_config_collections
);

"""
        ).execute()
        connection.prepareStatement(
            """create table if not exists ps_config_connections
(
    name text not null primary key,
    collection1 text not null references ps_config_collections,
    collection2 text not null references ps_config_collections,
    fields jsonb not null
);

"""
        ).execute()
    }

    override fun getDatabaseSchema(): DatabaseSchema {
        val collectionsResult = connection.prepareStatement("SELECT * FROM ps_config_collections").executeQuery()
        val connectionsResult = connection.prepareStatement("SELECT * FROM ps_config_connections").executeQuery()

        val collections = buildList {
            while (collectionsResult.next()) {
                val name = collectionsResult.getString("name")
                val schema = Json.decodeFromString<List<FieldDefinition>>(collectionsResult.getString("fields"))
                val parentCollection = collectionsResult.getString("parent_collection")
                add(
                    CollectionModel(
                        name,
                        schema.associate { it.name to DataType.valueOf(it.type.uppercase()) },
                        mutableListOf(),
                        parentCollection = parentCollection
                    )
                )
            }
        }
        // add child collections to schema
        addChildCollections(collections)

        val connections = buildList {
            while (connectionsResult.next()) {
                val name = connectionsResult.getString("name")
                val collection1 = connectionsResult.getString("collection1")
                val collection2 = connectionsResult.getString("collection2")
                val connectionDataSchema =
                    Json.decodeFromString<List<FieldDefinition>>(connectionsResult.getString("fields"))
                add(
                    ConnectionModel(
                        name,
                        collection1,
                        collection2,
                        connectionDataSchema.associate { it.name to DataType.valueOf(it.type.uppercase()) })
                )
            }
        }
        return DatabaseSchema(collections.toSet(), connections.toSet())
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
                        .append(quoteIdentifier(prevTable)).append(".").append(quoteIdentifier("_id"))
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
                        .append(quoteIdentifier(prevTable)).append(".").append(quoteIdentifier("_id"))
                        .append(" JOIN ").append(quoteIdentifier(nextTable))
                        .append(" ON ")
                        .append(quoteIdentifier(nextTable)).append(".")
                        .append(quoteIdentifier("_id")).append(" = ")
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
            is Condition.Comparison.Equals -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("${condition.field}")} = ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Comparison.GreaterThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("${condition.field}")} > ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Comparison.LessThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("${condition.field}")} < ${
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

            is Condition.In -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier("${condition.field}")} IN (${
                condition.list.joinToString(",") { prepareValue(it) }
            })"
        }
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_.][a-zA-Z0-9_.]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }

    private fun prepareValue(value: PolyValue): String {
        return when (value) {
            is PolyValue.StringValue, is PolyValue.UUIDValue -> {
                "'${value.value.toString()}'"
            }

            is PolyValue.IntValue, is PolyValue.FloatValue, is PolyValue.BooleanValue -> {
                value.value.toString()
            }

            is PolyValue.NullValue -> {
                "null"
            }
        }
    }

    private fun registerCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        val sql = StringBuilder()
        sql.append("INSERT INTO ps_config_collections VALUES (")
        sql.append(prepareValue(PolyValue.of(collectionName))).append(", ")
        sql.append("'").append(schema.toJson()).append("', ")
        if (parentCollectionName != null) {
            sql.append(prepareValue(PolyValue.of(parentCollectionName)))
        } else {
            sql.append("null")
        }
        sql.append(")")
        connection.prepareStatement(sql.toString()).execute()
    }

    private fun registerConnection(
        connectionName: String,
        collection1Name: String,
        collection2Name: String,
        schema: PolySchema
    ) {
        val sql = StringBuilder()
        sql.append("INSERT INTO ps_config_connections VALUES (")
        sql.append(prepareValue(PolyValue.of(connectionName))).append(", ")
        sql.append(prepareValue(PolyValue.of(collection1Name))).append(", ")
        sql.append(prepareValue(PolyValue.of(collection2Name))).append(", ")
        sql.append("'").append(schema.toJson()).append("')")

        connection.prepareStatement(sql.toString()).execute()
    }
}