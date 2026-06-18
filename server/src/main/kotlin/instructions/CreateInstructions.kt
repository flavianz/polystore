package ch.flavianz.instructions

import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.CollectionModel

data class CreateConnectionInstruction(val connection: ConnectionModel) : Instruction