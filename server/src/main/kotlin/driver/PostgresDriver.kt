package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.core.DatabaseManager.addChildCollections
import ch.flavianz.model.PolyData
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import ch.flavianz.query.QueryPath
import ch.flavianz.query.QuerySegment
import ch.flavianz.model.toJson
import ch.flavianz.query.Condition
import ch.flavianz.query.GetQuery
import ch.flavianz.query.PolyDriverQueryDuration
import ch.flavianz.server.FieldDefinition
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTimedValue

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
        sql.append(", _dynamic_data JSONB")

        sql.append(")")

        connection.prepareStatement(sql.toString()).execute()

        registerCollection(collectionName, schema, parentCollectionName)
    }

    override fun dropCollection(collection: CollectionModel) {
        val sql = StringBuilder()
        fun appendDropCollectionsRecursive(collection: CollectionModel) {
            for (child in collection.childCollections) {
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
        sql.append("; DELETE FROM ps_config_connections WHERE name = ${prepareValue(connectionModel.name)};")
        connection.prepareStatement(sql.toString()).execute()
        return
    }

    private fun tableDropStatement(collectionName: String): String {
        val sql = StringBuilder()
        sql.append("DROP TABLE ")
        sql.append(quoteIdentifier("ps_col_${collectionName}"))
        sql.append(";")
        sql.append("DELETE FROM ps_config_collections WHERE name = ${prepareValue(collectionName)};")
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
        sql.append(", _dynamic_data JSONB")

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

        val columnData = data.filter { collection.schema.containsKey(it.key) }
        val dynamicData = data - columnData.keys

        if (parentDocUuid != null) {
            sql.append(", ").append(quoteIdentifier("ps_parent_fk"))
        }

        for (entry in columnData.entries) {
            sql.append(", ").append(quoteIdentifier(entry.key))
        }
        sql.append(", _dynamic_data")

        sql.append(") VALUES (").append(prepareValue(uuid))

        if (parentDocUuid != null) {
            sql.append(", ").append(prepareValue(parentDocUuid))
        }

        for (entry in columnData.entries) {
            sql.append(", ").append(prepareValue(entry.value))
        }
        sql.append(", ").append(dynamicData.toJson())
        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }

    override fun updateDocument(collectionName: String, uuid: UUID, data: PolyData) {
        val sql = StringBuilder()
        sql.append("UPDATE ").append(quoteIdentifier("ps_col_${collectionName}")).append(" SET ")

        for (entry in data.entries) {
            sql.append(quoteIdentifier(entry.key)).append(" = ").append(prepareValue(entry.value))
                .append(", ")
        }
        if (data.isNotEmpty()) {
            // remove last comma
            sql.deleteRange(sql.length - 2, sql.length)
        }

        sql.append(" WHERE ").append(quoteIdentifier("_id")).append(" = ").append(
            prepareValue(
                uuid
            )
        )

        // TODO: enable updating dynamic data

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

        val columnData = connectionData.filter { connection.connectionDataSchema.containsKey(it.key) }
        val dynamicData = connectionData - columnData.keys

        for (entry in connectionData) {
            sql.append(", ").append(quoteIdentifier(entry.key))
        }
        sql.append(", _dynamic_data")
        sql.append(") VALUES (")

        sql.append(prepareValue(uuid1)).append(", ")
        sql.append(prepareValue(uuid2))

        for (entry in connectionData) {
            sql.append(", ").append(prepareValue(entry.value))
        }
        sql.append(", ").append(dynamicData.toJson())
        sql.append(")")

        this.connection.prepareStatement(sql.toString()).execute()
    }


    override fun get(query: GetQuery): TimedDriverResult<List<PolyData>> {
        val startTime = System.nanoTime()
        val sql = StringBuilder()

        val selectClauses = buildSelectClause(query.path.flatMap {
            when (it) {
                is QuerySegment.Collection -> listOf(Triple(it.name, it.only, false))
                is QuerySegment.Connection -> listOf(
                    Triple(it.connectionName, it.connectionOnly, true),
                    Triple(it.collectionName, it.collectionOnly, false)
                )
            }
        })

        sql.append("SELECT ").append(selectClauses)
        appendFromAndJoins(sql, query.path)
        appendWhere(sql, query.path)
        appendLimit(sql, query.limit)

        val data = measureTimedValue {
            val rs = connection.prepareStatement(sql.toString()).executeQuery()
            buildList {
                val metaData = rs.metaData
                val columnNames = (1..metaData.columnCount).map { metaData.getColumnName(it) }

                while (rs.next()) {
                    val fields = columnNames.associateWith {
                        rs.getObject(it)
                    }
                    add(fields)
                }
            }
        }

        val elapsedTime = (System.nanoTime() - startTime).nanoseconds
        return TimedDriverResult(
            data.value,
            PolyDriverQueryDuration(elapsedTime - data.duration, data.duration),
            listOf(sql.toString())
        )
    }

    /*override fun count(path: GetQuery, terminal: PolyTerminal.Count): PolyResultData.Count {
        val sql = StringBuilder()
        sql.append("SELECT COUNT(*) AS ps_count")
        appendFromAndJoins(sql, path)
        appendWhere(sql, path)

        val rs = connection.prepareStatement(sql.toString()).executeQuery()
        rs.next()
        return PolyResultData.Count(rs.getInt("ps_count"))
    }*/

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

    private fun buildSelectClause(
        segments: List<Triple<String, List<String>?, Boolean>>
    ): String {
        val projections = mutableListOf<String>()

        for (segment in segments) {
            val (segmentName, only, isConnection) = segment
            val schema =
                if (isConnection) DatabaseManager.getConnectionModel(segmentName).connectionDataSchema
                else DatabaseManager.getCollectionModel(segmentName).schema
            if (only == null) {
                if (isConnection) {
                    val model = DatabaseManager.getConnectionModel(segmentName)
                    for (f in model.connectionDataSchema.keys) {
                        projections.add(
                            "${quoteIdentifier("ps_con_${model.collection1Name}__${model.name}__${model.collection2Name}")}.${
                                quoteIdentifier(
                                    f
                                )
                            } AS ${quoteIdentifier("${segmentName}.$f")}"
                        )
                    }
                } else {
                    val model = DatabaseManager.getCollectionModel(segmentName)
                    projections.add(
                        "${quoteIdentifier("ps_col_${segmentName}")}.${quoteIdentifier("_id")} AS ${quoteIdentifier("${segmentName}._id")}"
                    )
                    for (f in model.schema.keys) {
                        projections.add(
                            "${quoteIdentifier("ps_col_${segmentName}")}.${quoteIdentifier(f)} AS ${quoteIdentifier("${segmentName}.$f")}"
                        )
                    }
                }
            } else {
                for (f in only) {
                    projections.add(
                        "${quoteIdentifier("ps_col_${segmentName}")}.${quoteIdentifier(f)} AS ${quoteIdentifier("${segmentName}.$f")}"
                    )
                }
            }
        }

        return projections.joinToString(", ")
    }

    private fun appendFromAndJoins(sql: StringBuilder, path: QueryPath) {
        val firstNode = path.first() as QuerySegment.Collection
        val firstTable = "ps_col_${firstNode.name}"
        sql.append(" FROM ").append(quoteIdentifier(firstTable))

        for (i in 1 until path.size) {
            when (val currentSegment = path[i]) {
                is QuerySegment.Collection -> {
                    val prevCol = path[i - 1].collectionName()
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
                    val prevCol = path[i - 1].collectionName()
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
        val conditions: List<String> = path.flatMap { segment ->
            i++
            buildList {
                when (segment) {
                    is QuerySegment.Collection -> {
                        if (segment.condition != null) {
                            val col = path[i - 1].collectionName()
                            val pgTable = "ps_col_${col}"
                            add(translateCondition(segment.condition, pgTable))
                        }
                    }

                    is QuerySegment.Connection -> {
                        if (segment.collectionCondition != null) {
                            val col = path[i - 1].collectionName()
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

    private fun appendLimit(sql: StringBuilder, limit: Int?) {
        limit?.let {
            sql.append(" limit $it")
        }
    }

    private fun translateCondition(condition: Condition, tableAlias: String): String {
        return when (condition) {
            is Condition.Comparison.Equals -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier(condition.field)} = ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Comparison.GreaterThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier(condition.field)} > ${
                prepareValue(
                    condition.value
                )
            }"

            is Condition.Comparison.LessThan -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier(condition.field)} < ${
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

            is Condition.In -> "${quoteIdentifier(tableAlias)}.${quoteIdentifier(condition.field)} IN (${
                condition.list.joinToString(",") { prepareValue(it) }
            })"
        }
    }

    private fun quoteIdentifier(name: String): String {
        // Reject anything that's not a safe identifier character
        require(name.matches("[a-zA-Z_.][a-zA-Z0-9_.]*".toRegex())) { "Invalid identifier: $name" }
        return "\"${name}\""
    }

    private fun prepareValue(value: Any?): String {
        return when (value) {
            is String, is UUID -> {
                "'$value'"
            }

            is Int, is Float, is Boolean -> {
                value.toString()
            }

            null -> {
                "null"
            }

            else -> throw IllegalArgumentException("illegal type ${value.javaClass.name}")
        }
    }

    private fun registerCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        val sql = StringBuilder()
        sql.append("INSERT INTO ps_config_collections VALUES (")
        sql.append(prepareValue(collectionName)).append(", ")
        sql.append("'").append(schema.toJson()).append("', ")
        if (parentCollectionName != null) {
            sql.append(prepareValue(parentCollectionName))
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
        sql.append(prepareValue(connectionName)).append(", ")
        sql.append(prepareValue(collection1Name)).append(", ")
        sql.append(prepareValue(collection2Name)).append(", ")
        sql.append("'").append(schema.toJson()).append("')")

        connection.prepareStatement(sql.toString()).execute()
    }

    private fun PolyData.toJson(): String {
        return buildString {
            append("{")
            for (entry in this@toJson) {
                append(
                    "\"${entry.key}\": ${
                        when (entry.value) {
                            is String, is UUID -> "\"${entry.value.toString()}\""
                            is Int, is Float, is Boolean, null -> entry.value.toString()
                            else -> throw IllegalArgumentException("unallowed data type")
                        }
                    }"
                )
                append(",")
            }
            deleteAt(length - 1)
            append("}::jsonb")
        }
    }
}