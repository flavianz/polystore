package ch.flavianz.query

import ch.flavianz.data.DataObject

data class InsertQuery(val collection: String, val data: DataObject) : Query