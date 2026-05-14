package ch.flavianz.exceptions

import ch.flavianz.data.PolyDocument
import ch.flavianz.model.ObjectSchema

data class ObjectSchemaMismatch(val polyDocument: PolyDocument, val objectSchema: ObjectSchema) : Exception() {
    override fun toString(): String {
        return "Object $polyDocument did not match schema $objectSchema"
    }
}