package ch.flavianz.instructions

import ch.flavianz.data.CollectionPathRef
import ch.flavianz.data.PolyDocument

data class InsertObjectInstruction(val collectionPathRef: CollectionPathRef, val data: PolyDocument) : Instruction