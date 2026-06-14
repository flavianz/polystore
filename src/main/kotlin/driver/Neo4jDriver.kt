package ch.flavianz.driver

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyData
import ch.flavianz.data.PolyValue
import ch.flavianz.model.ConnectionModel
import ch.flavianz.instructions.UpdateObjectInstruction
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import ch.flavianz.query.Condition
import ch.flavianz.query.FieldRef
import ch.flavianz.query.PolyResult
import ch.flavianz.query.PolyTerminal
import ch.flavianz.connection.Neo4jConnection
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.DatabaseSchema
import ch.flavianz.model.PolySchema
import java.util.UUID

class Neo4jDriver(val connection: Neo4jConnection) : DatabaseDriver {

    // Neo4j is schemaless — no DDL needed, but we validate the model is registered
    override fun createCollection(collectionName: String, schema: PolySchema, parentCollectionName: String?) {
        // No-op: Neo4j doesn't require schema creation.
        // Optionally create an index on ps_id for the label.
        connection.neo4jSession.use { session ->
            val label = collectionLabel(collectionName)
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:`$label`) ON (n.ps_id)")
        }
    }

    override fun createConnection(connection: ConnectionModel) {
        // No-op: relationships are created implicitly on insertConnection.
    }

    override fun insertDocument(collection: CollectionModel, uuid: UUID, data: PolyData, parentDocUuid: UUID?) {
        val label = collectionLabel(collection.name)
        val params = mutableMapOf<String, Any?>("ps_id" to uuid.toString())
        for ((key, value) in data) {
            params["ps_f_$key"] = value.toNeo4j()
        }

        connection.neo4jSession.use { session ->
            if (collection.hasParentCollection()) {
                val parentLabel = collectionLabel(collection.name)
                // Create child node and link to parent via ps_parent relationship
                session.run(
                    """
                    MATCH (parent:`$parentLabel` {ps_id: ${'$'}parentId})
                    CREATE (child:`$label`)
                    SET child = ${'$'}props
                    CREATE (parent)-[:ps_parent]->(child)
                    """.trimIndent(),
                    mapOf("parentId" to parentDocUuid, "props" to params)
                )
            } else {
                session.run(
                    "CREATE (n:`$label`) SET n = \$props",
                    mapOf("props" to params)
                )
            }
        }
    }

    override fun updateDocument(instruction: UpdateObjectInstruction) {
        val collectionRef = instruction.documentPath.parentCollection().toCollectionRef()
        val label = collectionLabel(collectionRef.leafName())
        val uuid = instruction.documentPath.uuid.toString()
        val updates = instruction.data.entries.joinToString(", ") { (key, _) ->
            "n.`ps_f_$key` = \$ps_f_$key"
        }
        val params = mutableMapOf<String, Any?>("ps_id" to uuid)
        for ((key, value) in instruction.data) {
            params["ps_f_$key"] = value.toNeo4j()
        }

        connection.neo4jSession.use { session ->
            session.run(
                "MATCH (n:`$label` {ps_id: \$ps_id}) SET $updates",
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

    override fun take(path: QueryPath, terminal: PolyTerminal.Take): List<PolyData> {
        val (cypher, aliases) = buildMatchClause(path)
        val returnClause = buildReturnClause(path, terminal.fields, aliases)
        val whereClause = buildWhereClause(path, aliases)

        val query = buildString {
            append(cypher)
            if (whereClause.isNotBlank()) append(" WHERE $whereClause")
            append(" RETURN $returnClause")
        }

        return connection.neo4jSession.use { session ->
            val result = session.run(query)
            result.list { record ->
                val map = mutableMapOf<String, PolyValue>()
                for (key in record.keys()) {
                    map[key] = record[key].toPolyValue()
                }
                map
            }
        }
    }

    override fun count(path: QueryPath, terminal: PolyTerminal.Count): PolyResult.Count {
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
            PolyResult.Count(result["ps_count"].asInt())
        }
    }

    override fun init() {
        TODO("Not yet implemented")
    }

    override fun getDatabaseSchema(): DatabaseSchema {
        TODO("Not yet implemented")
    }

    // ── Cypher builders ───────────────────────────────────────────────────────

    /**
     * Builds the MATCH chain for the query path.
     * Returns the Cypher string and a parallel list of Cypher variable names,
     * one per path segment (Collections → node alias, Connections → rel alias).
     */
    private fun buildMatchClause(path: QueryPath): Pair<String, List<String>> {
        val aliases = mutableListOf<String>()
        val sb = StringBuilder("MATCH ")
        var i = 0

        for (segment in path.segments) {
            when (segment) {
                is QuerySegment.Collection -> {
                    val alias = "n$i"
                    aliases.add(alias)
                    val label = collectionLabel(segment.name)
                    if (i == 0) {
                        sb.append("($alias:`$label`)")
                    } else {
                        // linked via ps_parent from previous node
                        sb.append("-[:ps_parent]->($alias:`$label`)")
                    }
                }

                is QuerySegment.Connection -> {
                    val conAlias = "r$i"
                    val nodeAlias = "n$i"
                    aliases.add(conAlias) // connection segment → rel alias
                    aliases.add(nodeAlias) // connection segment also owns the target node
                    val model = DatabaseManager.getConnectionModel(segment.connectionName)
                    val targetLabel = collectionLabel(model.collection2Name)
                    sb.append("-[$conAlias:`${model.name}`]->($nodeAlias:`$targetLabel`)")
                }
            }
            i++
        }

        return sb.toString() to aliases
    }

    /**
     * Builds the WHERE clause from all conditions in the path.
     */
    private fun buildWhereClause(path: QueryPath, aliases: List<String>): String {
        val parts = mutableListOf<String>()
        var aliasIndex = 0

        for (segment in path.segments) {
            when (segment) {
                is QuerySegment.Collection -> {
                    val alias = aliases[aliasIndex]
                    if (segment.condition != null) {
                        parts.add(translateCondition(segment.condition, alias))
                    }
                    aliasIndex++
                }

                is QuerySegment.Connection -> {
                    val relAlias = aliases[aliasIndex]
                    val nodeAlias = aliases[aliasIndex + 1]
                    if (segment.collectionCondition != null) {
                        parts.add(translateCondition(segment.collectionCondition, nodeAlias))
                    }
                    if (segment.connectionCondition != null) {
                        parts.add(translateCondition(segment.connectionCondition, relAlias))
                    }
                    aliasIndex += 2
                }
            }
        }

        return parts.joinToString(" AND ")
    }

    /**
     * Builds the RETURN clause, projecting only the requested fields.
     * Returns Cypher expressions like `n0.ps_f_name AS ps_col_student__name`.
     */
    private fun buildReturnClause(
        path: QueryPath,
        fields: List<FieldRef>,
        aliases: List<String>
    ): String {
        val projections = mutableListOf<String>()

        for (fieldRef in fields) {
            var aliasIndex = 0
            for (segment in path.segments) {
                when (segment) {
                    is QuerySegment.Collection -> {
                        if (segment.name == fieldRef.segment) {
                            val cyAlias = aliases[aliasIndex]
                            val model = DatabaseManager.getCollectionModel(segment.name)
                            when (fieldRef) {
                                is FieldRef.Wildcard -> {
                                    projections.add(
                                        "$cyAlias.ps_id AS `ps_col_${segment.name}__id`"
                                    )
                                    for (f in model.schema.keys) {
                                        projections.add(
                                            "$cyAlias.`ps_f_$f` AS `ps_col_${segment.name}__$f`"
                                        )
                                    }
                                }
                                is FieldRef.Named ->
                                    projections.add(
                                        "$cyAlias.`ps_f_${fieldRef.field}` AS `ps_col_${segment.name}__${fieldRef.field}`"
                                    )
                            }
                        }
                        aliasIndex++
                    }

                    is QuerySegment.Connection -> {
                        val relAlias = aliases[aliasIndex]
                        val nodeAlias = aliases[aliasIndex + 1]
                        val model = DatabaseManager.getConnectionModel(segment.connectionName)

                        if (segment.collectionName == fieldRef.segment) {
                            when (fieldRef) {
                                is FieldRef.Wildcard -> {
                                    projections.add(
                                        "$nodeAlias.ps_id AS `ps_col_${segment.collectionName}__id`"
                                    )
                                    for (f in DatabaseManager.getCollectionModel(segment.collectionName).schema.keys) {
                                        projections.add(
                                            "$nodeAlias.`ps_f_$f` AS `ps_col_${segment.collectionName}__$f`"
                                        )
                                    }
                                }
                                is FieldRef.Named ->
                                    projections.add(
                                        "$nodeAlias.`ps_f_${fieldRef.field}` AS `ps_col_${segment.collectionName}__${fieldRef.field}`"
                                    )
                            }
                        } else if (segment.connectionName == fieldRef.segment) {
                            when (fieldRef) {
                                is FieldRef.Wildcard ->
                                    for (f in model.connectionDataSchema.keys) {
                                        projections.add(
                                            "$relAlias.`ps_f_$f` AS `ps_con_${model.name}__$f`"
                                        )
                                    }
                                is FieldRef.Named ->
                                    projections.add(
                                        "$relAlias.`ps_f_${fieldRef.field}` AS `ps_con_${model.name}__${fieldRef.field}`"
                                    )
                            }
                        }
                        aliasIndex += 2
                    }
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

    private fun PolyValue.toNeo4j(): Any? = when (this) {
        is PolyValue.StringValue -> value
        is PolyValue.IntValue -> value
        is PolyValue.FloatValue -> value
        is PolyValue.BooleanValue -> value
        is PolyValue.UUIDValue -> value.toString()
        is PolyValue.NullValue -> null
    }

    private fun PolyValue.toCypherLiteral(): String = when (this) {
        is PolyValue.StringValue -> "'${value.replace("'", "\\'")}'"
        is PolyValue.UUIDValue -> "'${value}'"
        is PolyValue.IntValue -> value.toString()
        is PolyValue.FloatValue -> value.toString()
        is PolyValue.BooleanValue -> value.toString()
        is PolyValue.NullValue -> "null"
    }

    private fun org.neo4j.driver.Value.toPolyValue(): PolyValue = when {
        isNull -> PolyValue.NullValue
        type().name() == "STRING" -> {
            val s = asString()
            try { PolyValue.of(UUID.fromString(s)) } catch (_: IllegalArgumentException) { PolyValue.of(s) }
        }
        type().name() == "INTEGER" -> PolyValue.of(asInt())
        type().name() == "FLOAT" -> PolyValue.of(asFloat())
        type().name() == "BOOLEAN" -> PolyValue.of(asBoolean())
        else -> throw IllegalStateException("Unexpected Neo4j type: ${type().name()}")
    }
}