package ch.flavianz.instructions

import ch.flavianz.data.PolyDocument
import ch.flavianz.model.DocumentPath

data class UpdateObjectInstruction(val documentPath: DocumentPath, val data: PolyDocument) : Instruction