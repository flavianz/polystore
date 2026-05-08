package ch.flavianz.driver

import ch.flavianz.query.CreateQuery

interface DatabaseDriver {
    fun createCollection(createQuery: CreateQuery)
}
