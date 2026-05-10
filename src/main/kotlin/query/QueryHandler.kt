package ch.flavianz.query

import ch.flavianz.core.DatabaseManager

class QueryHandler {
    fun query(query: Query) {
        if(query is CreateCollectionQuery) {
            DatabaseManager.createCollection(query)
        } else if (query is CreateConnectionQuery) {
            DatabaseManager.createConnection(query.connection)
        }else if (query is InsertObjectQuery) {
            DatabaseManager.insertObject(query)
        }
    }
}