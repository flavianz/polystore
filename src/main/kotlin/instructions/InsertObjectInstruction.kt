package ch.flavianz.instructions

import ch.flavianz.data.PolyDocument
import ch.flavianz.model.CollectionPath

data class InsertObjectInstruction(val collectionPath: CollectionPath, val data: PolyDocument) : Instruction