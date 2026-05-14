package ch.flavianz.instructions

import ch.flavianz.core.DatabaseManager

class InstructionHandler {
    fun handle(instruction: Instruction) {
        when (instruction) {
            is CreateCollectionInstruction -> {
                DatabaseManager.createCollection(instruction)
            }
            is CreateConnectionInstruction -> {
                DatabaseManager.createConnection(instruction.connection)
            }
            is InsertObjectInstruction -> {
                DatabaseManager.insertObject(instruction)
            }
            is UpdateObjectInstruction -> {
                DatabaseManager.updateObject(instruction)
            }
            is QueryInstruction -> {
                println(DatabaseManager.query(instruction.query))
            }
        }
    }
}