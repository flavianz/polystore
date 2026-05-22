package ch.flavianz.instructions

import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.CollectionModel
import ch.flavianz.model.CollectionRef

data class CreateCollectionInstruction(val collectionModel: CollectionModel, val parentCollection: CollectionRef? = null) :
    Instruction
data class CreateConnectionInstruction(val connection: ConnectionModel) : Instruction