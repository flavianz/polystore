package ch.flavianz.instructions

import ch.flavianz.data.CollectionPathRef
import ch.flavianz.data.DataObject

data class InsertObjectInstruction(val collectionPathRef: CollectionPathRef, val data: DataObject) : Instruction