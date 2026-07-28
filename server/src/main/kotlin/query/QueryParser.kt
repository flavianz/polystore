package ch.flavianz.query

import ch.flavianz.data.PolyValue
import ch.flavianz.model.GetQuery
import ch.flavianz.model.QuerySegment
import java.util.UUID

class QueryParser(input: String) {

    private val tokens = tokenize(input)
    private var pos = 0

    fun parse(): GetQuery {
        if (tokens.isEmpty()) {
            return GetQuery(emptyList())
        }
        if (tokens[0] == "query") {
            return GetQuery(parseQuery())
        }
        throw IllegalStateException("unknown query type")
    }

    // "from a.(b where ...).c"
    private fun parseQuery(): List<QuerySegment> {
        expect("take")
        val nodes = mutableListOf<QuerySegment>()
        do {
            if (peek() == "-") {
                consume()
                nodes.add(parseConnectionSegment())
            } else {
                if (peek() == ".") consume()
                nodes.add(parseCollectionSegment())
            }
        } while (peek() == "." || peek() == "-")
        return nodes
    }


    private fun parseCollectionSegment(): QuerySegment.Collection {
        val (name, condition) = parseSegment()
        return QuerySegment.Collection(name, condition)
    }

    private fun parseConnectionSegment(): QuerySegment.Connection {
        val (connectionName, connectionCondition) = parseSegment()
        expect("-")
        val (collectionName, collectionCondition) = parseSegment()
        return QuerySegment.Connection(connectionName, collectionName, connectionCondition, collectionCondition)
    }

    // "(hospitals h where id = 3)" or just "doctors"
    private fun parseSegment(): Pair<String, Condition?> {
        val parenthesized = peek() == "("
        if (parenthesized) consume()

        val name = consumeIdentifier()

        val condition = if (peek() == "where") {
            consume()
            parseCondition()
        } else null

        if (parenthesized) consume(")")

        return name to condition
    }

    private fun consumeValue(): PolyValue {
        val token = consume()
        return when {
            token == "null" -> PolyValue.NullValue
            token.toIntOrNull() != null -> PolyValue.IntValue(token.toInt())
            isValidUUID(token) -> PolyValue.UUIDValue(UUID.fromString(token))
            else -> PolyValue.StringValue(token.trim('"'))
        }
    }

    private fun consumeNumberValue(): PolyValue.Number {
        val token = consume()
        return when {
            token.toIntOrNull() != null -> PolyValue.IntValue(token.toInt())
            else -> throw IllegalArgumentException("Expected number value, got $token")
        }
    }

    private fun parseCondition(): Condition {
        val field = consumeIdentifier()
        return when (val op = consume()) {
            "=" -> Condition.Comparison.Equals(field, consumeValue())
            ">" -> Condition.Comparison.GreaterThan(field, consumeNumberValue())
            "<" -> Condition.Comparison.LessThan(field, consumeNumberValue())
            else -> throw IllegalArgumentException("Unknown operator: $op")
        }
    }

    // --- Token helpers ---

    private fun tokenize(input: String): List<String> {
        return input.trim().split(Regex("\\s+|(?=[(),.-])|(?<=[(),.-])"))
            .filter { it.isNotBlank() }
    }

    private fun peek(): String = if (pos < tokens.size) tokens[pos] else ""
    private fun consume(): String = tokens[pos++]
    private fun consume(expected: String) {
        val token = consume()
        require(token == expected) { "Expected $expected, got $token" }
    }

    private fun expect(expected: String) = consume(expected)
    private fun isIdentifier(token: String) = token.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))
    private fun isValidUUID(token: String) = runCatching { UUID.fromString(token) }.isSuccess
    private fun consumeIdentifier(): String {
        val token = consume()
        require(isIdentifier(token)) { "Expected identifier, got $token" }
        return token
    }
}