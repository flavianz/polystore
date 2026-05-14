package ch.flavianz.query

import ch.flavianz.data.PolyValue
import java.util.UUID

class QueryParser(input: String) {

    private val tokens = tokenize(input)
    private var pos = 0

    fun parse(): PolyQuery {
        val path = parsePath()
        val terminal = parseTerminal()
        return PolyQuery(path, terminal)
    }

    // "from a.(b where ...).c"
    private fun parsePath(): List<PathNode> {
        expect("from")
        val nodes = mutableListOf<PathNode>()
        do {
            if (peek() == ".") consume()
            nodes.add(parsePathNode())
        } while (peek() == ".")
        return nodes
    }

    // "(hospitals h where id = 3)" or just "doctors"
    private fun parsePathNode(): PathNode {
        val parenthesized = peek() == "("
        if (parenthesized) consume()

        val name = consumeIdentifier()
        val alias = if (isIdentifier(peek())) consumeIdentifier() else null
        val condition = if (peek() == "where") {
            consume()
            parseCondition()
        } else null

        if (parenthesized) consume(")")
        return PathNode(name, alias, condition)
    }

    // "take ..." or "count"
    private fun parseTerminal(): PolyTerminal {
        return when (val keyword = consume()) {
            "take"  -> parseTake()
            "count" -> parseCount()
            else    -> throw IllegalArgumentException("Expected take or count, got $keyword")
        }
    }

    // "take h.name, d.name, doc.*"
    private fun parseTake(): PolyTerminal.Take {
        val fields = mutableListOf<FieldRef>()
        do {
            if (peek() == ",") consume()
            fields.add(parseFieldRef())
        } while (peek() == ",")
        return PolyTerminal.Take(fields)
    }

    // "count" or "count d"
    private fun parseCount(): PolyTerminal.Count {
        val alias = if (isIdentifier(peek())) consumeIdentifier() else null
        return PolyTerminal.Count(alias)
    }

    // "h.name" or "doc.*"
    private fun parseFieldRef(): FieldRef {
        val alias = consumeIdentifier()
        consume(".")
        val field = consume()
        return if (field == "*") FieldRef.Wildcard(alias)
        else FieldRef.Named(alias, field)
    }

    private fun consumeValue(): PolyValue {
        val token = consume()
        return when {
            token == "null"            -> PolyValue.NullValue
            token.toIntOrNull() != null -> PolyValue.IntValue(token.toInt())
            isValidUUID(token)         -> PolyValue.UUIDValue(UUID.fromString(token))
            else                       -> PolyValue.StringValue(token.trim('"'))
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
            "="  -> Condition.Equals(field, consumeValue())
            ">"  -> Condition.GreaterThan(field, consumeNumberValue())
            "<"  -> Condition.LessThan(field, consumeNumberValue())
            else -> throw IllegalArgumentException("Unknown operator: $op")
        }
    }

    // --- Token helpers ---

    private fun tokenize(input: String): List<String> {
        return input.trim().split(Regex("\\s+|(?=[(),.])|(?<=[(),.])")  )
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