package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.PolyData
import ch.flavianz.model.ConnectionModel
import ch.flavianz.query.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.connection.Neo4jConnection
import ch.flavianz.core.DatabaseManager.addChildCollections
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DataType
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import ch.flavianz.query.GetQuery
import ch.flavianz.query.PolyDriverQueryDuration
import ch.flavianz.server.FieldDefinition
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTimedValue

class Neo4jDriver(val connection: Neo4jConnection) : DatabaseDriver {

    // Neo4j is schemaless — no DDL needed, but we validate the model is registered
    override fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        // No-op: Neo4j doesn't require schema creation.
        // Optionally create an index on ps_id for the label.
        connection.neo4jSession.use { session ->
            val label = collectionLabel(collectionName)
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:`$label`) ON (n.ps_id)")
        }
        registerCollection(collectionName, schema, parentCollectionName)
    }

    override fun dropCollection(collection: CollectionModel) {
        dropCollectionRecursive(collection)
    }

    private fun dropCollectionRecursive(collection: CollectionModel) {
        for (child in collection.childCollections) {
            dropCollectionRecursive(DatabaseManager.getCollectionModel(child))
        }
        val label = collectionLabel(collection.name)
        connection.neo4jSession.use { session ->
            session.run(
                """
                MATCH (n:$label)
                DETACH DELETE n
            """
            )
        }
        connection.neo4jSession.use { session ->
            session.run(
                """
                MATCH (n:ps_config_collection)
                WHERE n.name = '${collection.name}'
                DETACH DELETE n
            """
            )
        }
    }

    override fun dropConnection(connectionModel: ConnectionModel) {
        connection.neo4jSession.use { session ->
            connection.neo4jSession.use { session ->
                session.run(
                    """
                MATCH (n:ps_config_connection)
                WHERE n.name = '${connectionModel.name}'
                DETACH DELETE n
            """
                )
            }
        }
    }

    override fun createConnection(connection: ConnectionModel) {
        // No-op: relationships are created implicitly on insertConnection.
        registerConnection(
            connection.name,
            connection.collection1Name,
            connection.collection2Name,
            connection.connectionDataSchema
        )
    }

    override fun insertDocument(collection: CollectionModel, uuid: UUID, data: PolyData, parentDocUuid: UUID?) {
        val label = collectionLabel(collection.name)
        val params = mutableMapOf<String, Any?>("ps_id" to uuid.toString())
        for ((key, value) in data) {
            params["ps_f_$key"] = value.toNeo4j()
        }

        connection.neo4jSession.use { session ->
            if (collection.hasParentCollection()) {
                val parentLabel = collectionLabel(collection.parentCollection!!)
                // Create child node and link to parent via ps_parent_of relationship
                session.run(
                    $$"""
                    MATCH (parent:`$$parentLabel` {ps_id: $parentId})
                    CREATE (child:`$$label`)
                    SET child = $props
                    CREATE (parent)-[:ps_parent_of]->(child)
                    """.trimIndent(),
                    mapOf("parentId" to parentDocUuid.toString(), "props" to params)
                )
            } else {
                session.run(
                    $$"CREATE (n:`$$label`) SET n = $props",
                    mapOf("props" to params)
                )
            }
        }
    }

    override fun updateDocument(collectionName: String, uuid: UUID, data: PolyData) {
        val label = collectionLabel(collectionName)
        val updates = data.entries.joinToString(", ") { (key, _) ->
            $$"n.`ps_f_$$key` = $ps_f_$$key"
        }
        val params = mutableMapOf<String, Any?>("ps_id" to uuid.toString())
        for ((key, value) in data) {
            params["ps_f_$key"] = value.toNeo4j()
        }

        connection.neo4jSession.use { session ->
            session.run(
                $$"MATCH (n:`$$label` {ps_id: $ps_id}) SET $$updates",
                params
            )
        }
    }

    override fun insertConnection(
        connection: ConnectionModel,
        collection1Name: String,
        uuid1: UUID,
        collection2Name: String,
        uuid2: UUID,
        connectionData: PolyData
    ) {
        val label1 = collectionLabel(collection1Name)
        val label2 = collectionLabel(collection2Name)
        val relType = connection.name
        val relProps = mutableMapOf<String, Any?>()
        for ((key, value) in connectionData) {
            relProps["ps_f_$key"] = value.toNeo4j()
        }

        this.connection.neo4jSession.use { session ->
            session.run(
                """
                MATCH (a:`$label1` {ps_id: ${'$'}uuid1})
                MATCH (b:`$label2` {ps_id: ${'$'}uuid2})
                CREATE (a)-[r:`$relType`]->(b)
                SET r = ${'$'}props
                """.trimIndent(),
                mapOf("uuid1" to uuid1.toString(), "uuid2" to uuid2.toString(), "props" to relProps)
            )
        }
    }

    override fun get(query: GetQuery): TimedDriverResult<List<PolyData>> {
        val startTime = System.nanoTime()
        val cypher = buildMatchClause(query)
        val returnClause = buildReturnClause(query.path.flatMap {
            when (it) {
                is QuerySegment.Collection -> listOf(Triple(it.name, it.only, false))
                is QuerySegment.Connection -> listOf(
                    Triple(it.connectionName, it.connectionOnly, true),
                    Triple(it.collectionName, it.collectionOnly, false)
                )
            }
        })
        val whereClause = buildWhereClause(query)

        // TODO: parameterize query with session.run(query, params) for better query caching performance
        val queryString = buildString {
            append(cypher)
            if (whereClause.isNotBlank()) append(" WHERE $whereClause")
            append(" RETURN $returnClause")
            query.limit?.let {
                append(" LIMIT $it")
            }
        }

        val result = measureTimedValue {
            connection.neo4jSession.use { session ->
                val result = session.run(queryString)
                result.list { record ->
                    val map = mutableMapOf<String, Any?>()
                    for (key in record.keys()) {
                        map[key] = record[key].toPolyValue()
                    }
                    map
                }
            }
        }

        val data = result.value

        val elapsedTime = (System.nanoTime() - startTime).nanoseconds
        return TimedDriverResult(
            data,
            PolyDriverQueryDuration(elapsedTime.minus(result.duration), result.duration),
            listOf(queryString)
        )
    }

    /*override fun count(path: GetQuery, terminal: PolyTerminal.Count): PolyResultData.Count {
        val (cypher, aliases) = buildMatchClause(path)
        val whereClause = buildWhereClause(path, aliases)
        val lastAlias = aliases.last()

        val query = buildString {
            append(cypher)
            if (whereClause.isNotBlank()) append(" WHERE $whereClause")
            append(" RETURN count($lastAlias) AS ps_count")
        }

        return connection.neo4jSession.use { session ->
            val result = session.run(query).single()
            PolyResultData.Count(result["ps_count"].asInt())
        }
    }*/

    override fun init() {
        connection.neo4jSession.use { session ->
            session.run(
                "CREATE INDEX IF NOT EXISTS FOR (n:ps_config_collection) ON (n.name)"
            )
            session.run(
                "CREATE INDEX IF NOT EXISTS FOR (n:ps_config_connection) ON (n.name)"
            )
        }
    }

    override fun getDatabaseSchema(): DatabaseSchema {
        return connection.neo4jSession.use { session ->
            val collectionRecords = session.run(
                "MATCH (n:ps_config_collection) RETURN n"
            ).list { it["n"].asNode() }

            val connectionRecords = session.run(
                "MATCH (n:ps_config_connection) RETURN n"
            ).list { it["n"].asNode() }

            fun parseFields(raw: String): PolySchema =
                Json.decodeFromString<List<FieldDefinition>>(raw)
                    .associate { it.name to DataType.valueOf(it.type.uppercase()) }

            val collections = collectionRecords.map { node ->
                CollectionModel(
                    node["name"].asString(),
                    parseFields(node["fields"].asString()),
                    mutableListOf(),
                    if (node.containsKey("parent_collection")) node["parent_collection"].asString() else null
                )
            }
            addChildCollections(collections)

            val connections = connectionRecords.map { node ->
                ConnectionModel(
                    node["name"].asString(),
                    node["collection1"].asString(),
                    node["collection2"].asString(),
                    parseFields(node["fields"].asString()),
                )
            }

            DatabaseSchema(collections.toSet(), connections.toSet())
        }
    }

    private fun registerCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        connection.neo4jSession.use { session ->
            session.run(
                $$"""
            CREATE (n:ps_config_collection {
                name: $name,
                fields: $fields,
                parent_collection: $parentCollection
            })
            """.trimIndent(),
                mapOf(
                    "name" to collectionName,
                    "fields" to Json.encodeToString(
                        schema.entries.map { FieldDefinition(it.key, it.value.name.lowercase()) }
                    ),
                    "parentCollection" to parentCollectionName
                )
            )
        }
    }

    private fun registerConnection(
        connectionName: String,
        collection1Name: String,
        collection2Name: String,
        schema: PolySchema
    ) {
        connection.neo4jSession.use { session ->
            session.run(
                """
            CREATE (n:ps_config_connection {
                name: ${'$'}name,
                collection1: ${'$'}collection1,
                collection2: ${'$'}collection2,
                fields: ${'$'}fields
            })
            """.trimIndent(),
                mapOf(
                    "name" to connectionName,
                    "collection1" to collection1Name,
                    "collection2" to collection2Name,
                    "fields" to Json.encodeToString(
                        schema.entries.map { FieldDefinition(it.key, it.value.name.lowercase()) }
                    )
                )
            )
        }
    }

    // ── Cypher builders ───────────────────────────────────────────────────────

    /**
     * Builds the MATCH chain for the query path.
     * Returns the Cypher string and a parallel list of Cypher variable names,
     * one per path segment (Collections → node alias, Connections → rel alias).
     */
    private fun buildMatchClause(path: GetQuery): String {
        val sb = StringBuilder("MATCH ")

        for ((i, segment) in path.path.withIndex()) {
            when (segment) {
                is QuerySegment.Collection -> {
                    val alias = segment.name
                    val label = collectionLabel(segment.name)
                    if (i == 0) {
                        sb.append("($alias:`$label`)")
                    } else {
                        // linked via ps_parent_of from previous node
                        sb.append("-[:ps_parent_of]->($alias:`$label`)")
                    }
                }

                is QuerySegment.Connection -> {
                    val conAlias = segment.connectionName
                    val nodeAlias = segment.collectionName
                    val model = DatabaseManager.getConnectionModel(segment.connectionName)
                    val targetLabel = collectionLabel(model.collection2Name)
                    sb.append("-[$conAlias:`${model.name}`]->($nodeAlias:`$targetLabel`)")
                }
            }
        }

        return sb.toString()
    }

    /**
     * Builds the WHERE clause from all conditions in the path.
     */
    private fun buildWhereClause(query: GetQuery): String {
        val parts = mutableListOf<String>()
        var aliasIndex = 0

        for (segment in query.path) {
            when (segment) {
                is QuerySegment.Collection -> {
                    if (segment.condition != null) {
                        parts.add(translateCondition(segment.condition, segment.name))
                    }
                    aliasIndex++
                }

                is QuerySegment.Connection -> {
                    if (segment.collectionCondition != null) {
                        parts.add(translateCondition(segment.collectionCondition, segment.collectionName))
                    }
                    if (segment.connectionCondition != null) {
                        parts.add(translateCondition(segment.connectionCondition, segment.connectionName))
                    }
                    aliasIndex += 2
                }
            }
        }

        return parts.joinToString(" AND ")
    }

    private fun buildReturnClause(
        segments: List<Triple<String, List<String>?, Boolean>>
    ): String {
        val projections = mutableListOf<String>()

        for (segment in segments) {
            val (segmentName, only, isConnection) = segment
            if (only == null) {
                if (isConnection) {
                    val model = DatabaseManager.getConnectionModel(segmentName)
                    for (f in model.connectionDataSchema.keys) {
                        projections.add(
                            "$segmentName.`ps_f_$f` AS `$segmentName.$f`"
                        )
                    }
                } else {
                    val model = DatabaseManager.getCollectionModel(segmentName)
                    projections.add(
                        "$segmentName.ps_id AS `$segmentName._id`"
                    )
                    for (f in model.schema.keys) {
                        projections.add(
                            "$segmentName.`ps_f_$f` AS `$segmentName.$f`"
                        )
                    }
                }
            } else {
                for (f in only) {
                    projections.add(
                        "$segmentName.`ps_f_$f` AS `$segmentName.$f`"
                    )
                }
            }
        }

        return projections.joinToString(", ")
    }

    private fun translateCondition(condition: Condition, cyAlias: String): String {
        return when (condition) {
            is Condition.Comparison.Equals ->
                "$cyAlias.`ps_f_${condition.field}` = ${condition.value.toCypherLiteral()}"

            is Condition.Comparison.GreaterThan ->
                "$cyAlias.`ps_f_${condition.field}` > ${condition.value.toCypherLiteral()}"

            is Condition.Comparison.LessThan ->
                "$cyAlias.`ps_f_${condition.field}` < ${condition.value.toCypherLiteral()}"

            is Condition.Logic.And ->
                "(${translateCondition(condition.left, cyAlias)} AND ${translateCondition(condition.right, cyAlias)})"

            is Condition.Logic.Or ->
                "(${translateCondition(condition.left, cyAlias)} OR ${translateCondition(condition.right, cyAlias)})"

            is Condition.Not ->
                "NOT (${translateCondition(condition.condition, cyAlias)})"

            is Condition.In ->
                "$cyAlias.`ps_f_${condition.field}` IN [${condition.list.joinToString(", ") { it.toCypherLiteral() }}]"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectionLabel(name: String): String = "ps_col_$name"

    private fun Any?.toNeo4j(): Any? = when (this) {
        is String, is Int, is Float, is Boolean -> this
        is UUID -> this.toString()
        null -> null
        else -> throw IllegalArgumentException("illegal type ${this.javaClass.name}")
    }

    private fun Any?.toCypherLiteral(): String = when (this) {
        is String -> "'${this.replace("'", "\\'")}'"
        is UUID -> this.toString().toCypherLiteral()
        is Int, is Float, is Boolean -> this.toString()
        null -> "null"
        else -> throw IllegalArgumentException("illegal type ${this.javaClass.name}")
    }

    private fun org.neo4j.driver.Value.toPolyValue(): Any? = when {
        isNull -> null
        type().name() == "STRING" -> {
            val s = asString()
            if (s.length != 36) {
                return s
            }
            try {
                UUID.fromString(s)
            } catch (_: IllegalArgumentException) {
                s
            }
        }

        type().name() == "INTEGER" -> asInt()
        type().name() == "FLOAT" -> asFloat()
        type().name() == "BOOLEAN" -> asBoolean()
        else -> throw IllegalStateException("Unexpected Neo4j type: ${type().name()}")
    }
}