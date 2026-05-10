package ch.flavianz.query

import ch.flavianz.data.DataObject
import ch.flavianz.data.DocumentPathRef

data class UpdateObjectQuery(val documentPathRef: DocumentPathRef, val data: DataObject) : Query