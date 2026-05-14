package ch.flavianz.instructions

import ch.flavianz.data.PolyDocument
import ch.flavianz.data.DocumentPathRef

data class UpdateObjectInstruction(val documentPathRef: DocumentPathRef, val data: PolyDocument) : Instruction