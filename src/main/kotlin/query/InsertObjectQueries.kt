package ch.flavianz.query

import ch.flavianz.data.CollectionPathRef
import ch.flavianz.data.DataObject

data class InsertObjectQuery(val collectionPathRef: CollectionPathRef, val data: DataObject) : Query