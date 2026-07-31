package ch.flavianz.query

infix fun String.eq(value: Any?) = Condition.Comparison.Equals(this, value)
infix fun String.gt(value: Number) = Condition.Comparison.GreaterThan(this, value)
infix fun String.lt(value: Number) = Condition.Comparison.LessThan(this, value)
infix fun Condition.and(value: Condition) = Condition.Logic.And(this, value)
infix fun Condition.or(value: Condition) = Condition.Logic.Or(this, value)
fun not(condition: Condition) = Condition.Not(condition)
infix fun String.isIn(list: Collection<Any?>) = Condition.In(this, list.toSet())