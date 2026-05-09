package ch.flavianz.query

import ch.flavianz.data.CollectionRef
import ch.flavianz.data.DataObject

data class InsertRootObjectQuery(val collection: CollectionRef, val data: DataObject) : Query