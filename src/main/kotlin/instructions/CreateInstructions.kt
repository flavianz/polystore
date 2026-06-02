package ch.flavianz.instructions

import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.CollectionModel

data class CreateCollectionInstruction(val collectionModel: CollectionModel, val parentCollectionName: String? = null) :
    Instruction

data class CreateConnectionInstruction(val connection: ConnectionModel) : Instruction