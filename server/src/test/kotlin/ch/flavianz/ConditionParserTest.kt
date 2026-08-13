package ch.flavianz

import query.Condition
import server.ConditionParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConditionParserTest {

    @Test
    fun `parses equals comparison`() {
        val condition = ConditionParser("""x == "hello"""").parse()

        val equals = assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            condition
        )

        assertEquals("x", equals.field)
    }

    @Test
    fun `parses greater than`() {
        val condition = ConditionParser("age > 18").parse()

        val gt = assertInstanceOf(
            Condition.Comparison.GreaterThan::class.java,
            condition
        )

        assertEquals("age", gt.field)
    }

    @Test
    fun `parses less than`() {
        val condition = ConditionParser("score < 100").parse()

        val lt = assertInstanceOf(
            Condition.Comparison.LessThan::class.java,
            condition
        )

        assertEquals("score", lt.field)
    }

    @Test
    fun `parses in operator`() {
        val condition = ConditionParser(
            """status in ["active", "pending"]"""
        ).parse()

        val inCondition = assertInstanceOf(
            Condition.In::class.java,
            condition
        )

        assertEquals("status", inCondition.field)
        assertEquals(2, inCondition.list.size)
    }

    @Test
    fun `parses negation`() {
        val condition = ConditionParser(
            """!(x == 10)"""
        ).parse()

        val not = assertInstanceOf(
            Condition.Not::class.java,
            condition
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            not.condition
        )
    }

    @Test
    fun `parses and`() {
        val condition = ConditionParser(
            "x > 5 && y < 10"
        ).parse()

        val and = assertInstanceOf(
            Condition.Logic.And::class.java,
            condition
        )

        assertInstanceOf(
            Condition.Comparison.GreaterThan::class.java,
            and.left
        )

        assertInstanceOf(
            Condition.Comparison.LessThan::class.java,
            and.right
        )
    }

    @Test
    fun `parses or`() {
        val condition = ConditionParser(
            """x == 1 || y == 2"""
        ).parse()

        val or = assertInstanceOf(
            Condition.Logic.Or::class.java,
            condition
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            or.left
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            or.right
        )
    }

    @Test
    fun `and has higher precedence than or`() {
        val condition = ConditionParser(
            "a == 1 || b == 2 && c == 3"
        ).parse()

        val or = assertInstanceOf(
            Condition.Logic.Or::class.java,
            condition
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            or.left
        )

        val and = assertInstanceOf(
            Condition.Logic.And::class.java,
            or.right
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            and.left
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            and.right
        )
    }

    @Test
    fun `parentheses override precedence`() {
        val condition = ConditionParser(
            "(a == 1 || b == 2) && c == 3"
        ).parse()

        val and = assertInstanceOf(
            Condition.Logic.And::class.java,
            condition
        )

        assertInstanceOf(
            Condition.Logic.Or::class.java,
            and.left
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            and.right
        )
    }

    @Test
    fun `parses complex example`() {
        val condition = ConditionParser(
            """val > 55 && !(x == "10" || y in ["a", 17])"""
        ).parse()

        val and = assertInstanceOf(
            Condition.Logic.And::class.java,
            condition
        )

        assertInstanceOf(
            Condition.Comparison.GreaterThan::class.java,
            and.left
        )

        val not = assertInstanceOf(
            Condition.Not::class.java,
            and.right
        )

        val or = assertInstanceOf(
            Condition.Logic.Or::class.java,
            not.condition
        )

        assertInstanceOf(
            Condition.Comparison.Equals::class.java,
            or.left
        )

        assertInstanceOf(
            Condition.In::class.java,
            or.right
        )
    }

    @Test
    fun `throws on invalid syntax`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionParser("x ==")
                .parse()
        }
    }

    @Test
    fun `throws on unmatched parentheses`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionParser("(x == 1")
                .parse()
        }
    }
}