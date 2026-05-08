package ch.flavianz.query

import ch.flavianz.data.CollectionRef
import ch.flavianz.model.CollectionConnection
import ch.flavianz.model.CollectionModel

data class CreateCollectionQuery(val parentCollection: CollectionRef, val collectionModel: CollectionModel) : Query
data class CreateConnectionQuery(val connection: CollectionConnection) : Query