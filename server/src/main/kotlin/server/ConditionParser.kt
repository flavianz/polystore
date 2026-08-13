package server

import query.Condition
import java.util.UUID

class ConditionParser(input: String?) {
    private val tokens = tokenize(input)
    private var pos = 0

    fun parse(): Condition? {
        if (tokens.isEmpty()) {
            return null
        }
        val result = parseOr()

        require(pos == tokens.size) {
            "Unexpected token '${peek()}'"
        }

        return result
    }

    private fun parseOr(): Condition {
        var left = parseAnd()

        while (peek() == "||") {
            consume()
            val right = parseAnd()
            left = Condition.Logic.Or(left, right)
        }

        return left
    }

    private fun parseAnd(): Condition {
        var left = parseUnary()

        while (peek() == "&&") {
            consume()
            val right = parseUnary()
            left = Condition.Logic.And(left, right)
        }

        return left
    }

    private fun parseUnary(): Condition {
        if (peek() == "!") {
            consume()
            return Condition.Not(parseUnary())
        }

        return parsePrimary()
    }

    private fun parsePrimary(): Condition {
        if (peek() == "(") {
            consume()

            val condition = parseOr()

            expect(")")
            return condition
        }

        return parseComparison()
    }

    private fun parseComparison(): Condition {
        val identifier = consume()

        require(isIdentifier(identifier)) {
            "Expected identifier, got '$identifier'"
        }

        return when (val operator = consume().lowercase()) {
            "in" -> {
                expect("[")

                val items = mutableListOf<Any?>()

                if (peek() != "]") {
                    while (true) {
                        items += consumeValue()

                        if (peek() == "]")
                            break

                        expect(",")
                    }
                }

                expect("]")

                Condition.In(identifier, items.toSet())
            }

            "==" -> {
                Condition.Comparison.Equals(
                    identifier,
                    consumeValue()
                )
            }

            "!=" -> {
                Condition.Not(
                    Condition.Comparison.Equals(
                        identifier,
                        consumeValue()
                    )
                )
            }

            "<" -> {
                Condition.Comparison.LessThan(
                    identifier,
                    consumeNumberValue()
                )
            }

            ">" -> {
                Condition.Comparison.GreaterThan(
                    identifier,
                    consumeNumberValue()
                )
            }

            else -> error("Unsupported operator '$operator'")
        }
    }

    private fun consumeValue(): Any? {
        val token = consume()

        if (token.startsWith("'") && token.endsWith("'")) {
            return token.substring(1, token.length - 1)
        }

        if (token.equals("true", true))
            return true

        if (token.equals("false", true))
            return false

        if (token.equals("null", true))
            return null

        token.toIntOrNull()?.let {
            return it
        }

        token.toFloatOrNull()?.let {
            return it
        }

        if (isValidUUID(token)) {
            return UUID.fromString(token)
        }

        throw IllegalArgumentException("Illegal value '$token'")
    }

    private fun consumeNumberValue(): Number {
        val token = consume()

        token.toIntOrNull()?.let {
            return it
        }

        token.toFloatOrNull()?.let {
            return it
        }

        throw IllegalArgumentException("Expected numeric value, got '$token'")
    }

    private fun tokenize(input: String?): List<String> {
        if (input == null) {
            return emptyList()
        }
        val regex = Regex(
            "\"[^\"]*\"" +
                    "|'[^']*'" +
                    "|&&" +
                    "|\\|\\|" +
                    "|==" +
                    "|!=" +
                    "|<=" +
                    "|>=" +
                    "|[()\\[\\],!<>]" +
                    "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" +
                    "|[a-zA-Z_][a-zA-Z0-9_]*" +
                    "|\\d+\\.\\d+" +
                    "|\\d+"
        )

        return regex.findAll(input)
            .map { it.value }
            .toList()
    }

    private fun peek(): String =
        if (pos < tokens.size) tokens[pos] else ""

    private fun consume(): String {
        require(pos < tokens.size) {
            "Unexpected end of input"
        }

        return tokens[pos++]
    }

    private fun expect(expected: String) {
        val actual = consume()

        require(actual == expected) {
            "Expected '$expected', got '$actual'"
        }
    }

    private fun isIdentifier(token: String): Boolean =
        token.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))

    private fun isValidUUID(token: String): Boolean =
        token.length == 36 && runCatching { UUID.fromString(token) }.isSuccess
}