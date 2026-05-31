package ch.flavianz.instructions

import ch.flavianz.data.PolyData
import ch.flavianz.model.DocumentPath

data class UpdateObjectInstruction(val documentPath: DocumentPath, val data: PolyData) : Instruction