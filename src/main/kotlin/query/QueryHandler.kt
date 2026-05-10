package ch.flavianz.query

import ch.flavianz.core.DatabaseManager

class QueryHandler {
    fun query(query: Query) {
        when (query) {
            is CreateCollectionQuery -> {
                DatabaseManager.createCollection(query)
            }
            is CreateConnectionQuery -> {
                DatabaseManager.createConnection(query.connection)
            }
            is InsertObjectQuery -> {
                DatabaseManager.insertObject(query)
            }
            is UpdateObjectQuery -> {
                DatabaseManager.updateObject(query)
            }
        }
    }
}