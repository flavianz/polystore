package ch.flavianz.query

import ch.flavianz.core.DatabaseManager
import ch.flavianz.data.PolyValue
import ch.flavianz.model.QueryPath
import ch.flavianz.model.QuerySegment
import java.util.UUID

class QueryParser(input: String) {

    private val tokens = tokenize(input)
    private var pos = 0
    private val aliasMap = mutableMapOf<String, String>()

    fun parse(): PolyQuery {
        val pathSegments = parsePath()
        val terminal = parseTerminal()
        return PolyQuery(QueryPath(pathSegments), terminal)
    }

    // "from a.(b where ...).c"
    private fun parsePath(): List<QuerySegment> {
        expect("from")
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
        val (name, condition) = parseSegment()
        return QuerySegment.Connection(name, condition)
    }

    // "(hospitals h where id = 3)" or just "doctors"
    private fun parseSegment(): Pair<String, Condition?>{
        val parenthesized = peek() == "("
        if (parenthesized) consume()

        val name = consumeIdentifier()

        // if next token is an identifier (not "where", ".", ")"), it's an alias
        val nextIsAlias = isIdentifier(peek()) && peek() !in arrayOf("where", "count", "take")
        if (nextIsAlias) {
            val alias = consumeIdentifier()
            aliasMap[alias] = name  // register alias → real name, then discard alias
        }

        val condition = if (peek() == "where") {
            consume()
            parseCondition()
        } else null

        if (parenthesized) consume(")")

        return name to condition
    }

    // "take ..." or "count"
    private fun parseTerminal(): PolyTerminal {
        println(tokens)
        println(pos)
        println(aliasMap)
        return when (val keyword = consume()) {
            "take"  -> parseTake()
            "count" -> PolyTerminal.Count
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

    // "h.name" or "doc.*"
    private fun parseFieldRef(): FieldRef {
        val aliasOrName = consumeIdentifier()
        val resolvedName = aliasMap[aliasOrName] ?: aliasOrName  // resolve, fall back to name itself
        consume(".")
        val field = consume()
        return if (field == "*") FieldRef.Wildcard(resolvedName)
        else FieldRef.Named(resolvedName, field)
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