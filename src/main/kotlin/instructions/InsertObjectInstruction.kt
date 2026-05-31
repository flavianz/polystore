package ch.flavianz.instructions

import ch.flavianz.data.PolyData
import ch.flavianz.model.CollectionPath

data class InsertObjectInstruction(val collectionPath: CollectionPath, val data: PolyData) : Instruction