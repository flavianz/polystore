package ch.flavianz.query

import ch.flavianz.core.DatabaseManager

class QueryHandler {
    fun query(query: Query) {
        if(query is CreateQuery) {
            DatabaseManager.createCollection(query)
        } else if (query is InsertQuery) {
            //DatabaseManager.
        }
    }
}