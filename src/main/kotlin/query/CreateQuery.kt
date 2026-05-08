package ch.flavianz.query

import ch.flavianz.data.CollectionRef
import ch.flavianz.model.CollectionModel

data class CreateQuery(val collectionModel: CollectionModel, val parentCollection: CollectionRef) : Query