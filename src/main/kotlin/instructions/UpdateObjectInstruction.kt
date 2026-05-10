package ch.flavianz.instructions

import ch.flavianz.data.DataObject
import ch.flavianz.data.DocumentPathRef

data class UpdateObjectInstruction(val documentPathRef: DocumentPathRef, val data: DataObject) : Instruction