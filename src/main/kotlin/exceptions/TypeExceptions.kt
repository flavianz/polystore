package ch.flavianz.exceptions

import ch.flavianz.data.DataObject
import ch.flavianz.model.ObjectSchema
import kotlin.reflect.KClass

data class TypeMismatchException(val value: Any, val expectedType: KClass<*>) : Exception() {
    override fun toString(): String {
        return "Type mismatch: Expected \"${value}\" to be of type $expectedType"
    }
}

data class ObjectSchemaMismatch(val dataObject: DataObject, val objectSchema: ObjectSchema) : Exception() {
    override fun toString(): String {
        return "Object $dataObject did not match schema $objectSchema"
    }
}