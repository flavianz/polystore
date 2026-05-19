package ch.flavianz.instructions

import ch.flavianz.data.CollectionRef
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.CollectionModel

data class CreateCollectionInstruction(val parentCollection: CollectionRef, val collectionModel: CollectionModel) :
    Instruction
data class CreateConnectionInstruction(val connection: ConnectionModel) : Instruction