package benchmark.regression

import benchmark.Benchmark
import benchmark.Benchmark.faker
import ch.flavianz.query.GetQueryBuilder
import ch.flavianz.query.get
import core.DatabaseManager
import model.ConnectionModel
import model.DataType
import model.PolyData
import query.QueryPath
import query.QuerySegment
import java.util.UUID
import kotlin.random.asKotlinRandom

class BenchEnvironmentRegression {
    val userCollections = listOf(
        "users", "children", "grand_children", "grand_grand_children",
        "grand_grand_grand_children", "grand_grand_grand_grand_children", "grand_grand_grand_grand_grand_children"
    )
    val petCollections = listOf("pets", "pets_child", "pets_grand_child")

    val userSchema = mapOf(
        "name" to DataType.STRING,
        "age" to DataType.INT,
        "male" to DataType.BOOLEAN
    )

    fun generateUserDoc(): PolyData {
        return mapOf(
            "name" to faker.name().firstName(),
            "last" to faker.name().firstName(),
            "age" to faker.number().numberBetween(0, 100),
            "male" to faker.bool().bool(),
            "income" to faker.number().numberBetween(0, 100_000)
        )
    }

    val petSchema = mapOf(
        "animal" to DataType.STRING,
        "age" to DataType.INT,
        "male" to DataType.BOOLEAN
    )

    fun generatePetDoc(): PolyData {
        return mapOf(
            "animal" to faker.animal().species(),
            "name" to faker.animal().name(),
            "age" to faker.number().numberBetween(0, 20),
            "male" to faker.bool().bool(),
            "legs" to faker.number().numberBetween(0, 9)
        )
    }

    val connectionSchema = mapOf(
        "since" to DataType.STRING,
        "likes" to DataType.BOOLEAN,
        "nickname" to DataType.STRING
    )

    fun generateConnectionData(): PolyData {
        return mapOf(
            "nickname" to faker.animal().name(),
            "since" to faker.number().numberBetween(0, 20),
            "likes" to faker.bool().bool(),
        )
    }

    val collections = listOf(
        Triple(userCollections, userSchema, ::generateUserDoc),
        Triple(petCollections, petSchema, ::generatePetDoc)
    )

    val collectionSizes = listOf(100, 500, 1000, 3000, 9000, 15000)

    val ids = mutableMapOf<String, MutableList<UUID>>()

    fun init() {

        val collectionDepthPairs = buildList {
            for (i in 0..<7) {
                for (j in 0..<3) {
                    add(i to j)
                }
            }
        }

        val collectionDepthTriples = buildList {
            for (i in 0..<6) {
                for (j in 0..<3) {
                    for (k in 0..<(7 - i)) {
                        add(Triple(i, j, k))
                    }
                }
            }
        }

        val collectionDepthDeepTriple = buildList {
            for (i in 0..<6) {
                for (j in 0..<2) {
                    for (k in i..<7) {
                        add(Triple(i, j, k))
                    }
                }
            }
        }

        val queries = buildList {
            // simple sub collection queries
            for (i in 0..<7) {
                add(get {
                    for (i in 0..i) {
                        collection(userCollections[i])
                    }
                })
            }
            // with one connection
            for (collectionDepthPair in collectionDepthPairs) {
                add(get {
                    for (i in 0..collectionDepthPair.first) {
                        collection(userCollections[i])
                    }
                    connection(
                        "${userCollections[collectionDepthPair.first]}_owns_${petCollections[0]}",
                        petCollections[0]
                    )
                    for (i in 1..collectionDepthPair.second) {
                        collection(petCollections[i])
                    }
                })
            }
            // with one connection, reverse direction
            for (collectionDepthPair in collectionDepthPairs) {
                add(get {
                    for (i in 0..collectionDepthPair.second) {
                        collection(petCollections[i])
                    }
                    connection(
                        "${userCollections[0]}_owns_${petCollections[collectionDepthPair.second]}",
                        userCollections[0]
                    )
                    for (i in 1..collectionDepthPair.first) {
                        collection(userCollections[i])
                    }
                })
            }
            // with two connections
            for (collectionDepthTriple in collectionDepthTriples) {
                add(get {
                    for (i in 0..collectionDepthTriple.first) {
                        collection(userCollections[i])
                    }
                    connection(
                        "${userCollections[collectionDepthTriple.first]}_owns_${petCollections[0]}",
                        petCollections[0]
                    )
                    for (i in 1..collectionDepthTriple.second) {
                        collection(petCollections[i])
                    }
                    connection(
                        "${userCollections[collectionDepthTriple.first + 1]}_owns_${petCollections[collectionDepthTriple.second]}",
                        userCollections[collectionDepthTriple.first + 1]
                    )
                    for (i in (collectionDepthTriple.first + 2)..collectionDepthTriple.third) {
                        collection(userCollections[i])
                    }
                })
            }

            // with three connections
            for (collectionDepthDeepTriple in collectionDepthDeepTriple) {
                add(get {
                    for (i in 0..collectionDepthDeepTriple.first) {
                        collection(userCollections[i])
                    }
                    connection(
                        "${userCollections[collectionDepthDeepTriple.first]}_owns_${petCollections[0]}",
                        petCollections[0]
                    )
                    for (i in 1..collectionDepthDeepTriple.second) {
                        collection(petCollections[i])
                    }
                    connection(
                        "${userCollections[collectionDepthDeepTriple.first + 1]}_owns_${petCollections[collectionDepthDeepTriple.second]}",
                        userCollections[collectionDepthDeepTriple.first + 1]
                    )
                    for (i in (collectionDepthDeepTriple.first + 2)..collectionDepthDeepTriple.third) {
                        collection(userCollections[i])
                    }
                    connection(
                        "${userCollections[collectionDepthDeepTriple.third]}_owns_${petCollections[collectionDepthDeepTriple.second + 1]}",
                        petCollections[collectionDepthDeepTriple.second + 1]
                    )
                    for (i in (collectionDepthDeepTriple.second + 2)..<3) {
                        collection(petCollections[i])
                    }
                })
            }
        }

        val conditionQueries = buildList {
            for(conditionCount in 0..<11) {
                for (query in queries) {

                }
            }
        }

        DatabaseManager.dropAllConnections()
        DatabaseManager.dropAllCollections()

        // create all collections
        for ((collectionGroup, collectionSchema) in collections) {
            for ((i, collectionName) in collectionGroup.withIndex()) {
                DatabaseManager.createCollection(
                    collectionName,
                    collectionSchema,
                    if (i == 0) null else collectionGroup[i - 1]
                )
            }
        }
        // create a connection between each pair of collections
        for (userCollection in userCollections) {
            for (petCollection in petCollections) {
                DatabaseManager.createConnection(
                    ConnectionModel(
                        "${userCollection}_owns_$petCollection",
                        userCollection,
                        petCollection,
                        connectionSchema
                    )
                )
            }
        }

        // insert all documents step for step
        var currentCollectionSize = 0
        for (collectionSize in collectionSizes) {
            // insert documents in collections
            for ((collectionGroup, _, docGenerator) in collections) {
                for ((i, collectionName) in collectionGroup.withIndex()) {
                    val currentIdList = ids[collectionName] ?: mutableListOf()
                    val parentIds = if (i == 0) emptyList() else ids[collectionGroup[i - 1]] ?: emptyList()
                    repeat(collectionSize - currentCollectionSize) {
                        currentIdList.add(
                            DatabaseManager.insertDocument(
                                collectionName,
                                docGenerator(),
                                if (i == 0) null else parentIds.random(Benchmark.seed.asKotlinRandom())
                            )
                        )
                    }
                    ids[collectionName] = currentIdList
                }
            }

            // insert connections between each pair of collections
            for (userCollection in userCollections) {
                for (petCollection in petCollections) {
                    val userUuids = ids[userCollection] ?: emptyList()
                    val petUuids = ids[petCollection] ?: emptyList()
                    repeat((collectionSize - currentCollectionSize) / 10) {
                        DatabaseManager.insertConnection(
                            "${userCollection}_owns_$petCollection",
                            userCollection, userUuids.random(Benchmark.seed.asKotlinRandom()),
                            petCollection, petUuids.random(Benchmark.seed.asKotlinRandom()), generateConnectionData()
                        )
                    }
                }
            }

            // hier

            currentCollectionSize = collectionSize
        }
    }

    private fun generateQueryPaths(
        userIndex: Int,
        petIndex: Int,
        remainingDepth: Int,
        remainingConnectionCount: Int,
        currentSide: Side
    ): List<List<QuerySegment>> {
        if (remainingDepth == 0) {
            return listOf(listOf(QuerySegment.Collection(if (currentSide == Side.Pet) petCollections[petIndex] else userCollections[userIndex])))
        }
        val subCollectionPaths =
            if ((currentSide == Side.User && userIndex < 6) || (currentSide == Side.Pet && petIndex < 2)) generateQueryPaths(
                if (currentSide == Side.User) userIndex + 1 else userIndex,
                if (currentSide == Side.Pet) userIndex + 1 else userIndex,
                remainingDepth - 1,
                remainingConnectionCount,
                currentSide
            ) else emptyList()
        val connectionPaths =
            if (remainingConnectionCount > 0 && ((currentSide == Side.User && petIndex < 2) || (currentSide == Side.Pet && userIndex < 6))) generateQueryPaths(
                if (currentSide == Side.User) userIndex else userIndex + 1,
                if (currentSide == Side.Pet) petIndex else petIndex + 1,
                remainingDepth - 1,
                remainingConnectionCount + 1,
                if (currentSide == Side.Pet) Side.User else Side.Pet
            ) else emptyList()
        return subCollectionPaths
            .map { it + QuerySegment.Collection(if (currentSide == Side.Pet) petCollections[petIndex] else userCollections[userIndex]) } +
                connectionPaths.map {
                    val collectionName =
                        if (currentSide == Side.Pet) userCollections[userIndex] else petCollections[petIndex]
                    val connectionName = "${userCollections[userIndex]}_owns_${petCollections[petIndex]}"
                    it + QuerySegment.Connection(connectionName, collectionName)
                }
    }
}

private enum class Side {
    User,
    Pet
}