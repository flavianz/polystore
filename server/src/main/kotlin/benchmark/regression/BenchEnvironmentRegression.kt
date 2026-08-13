package benchmark.regression

import benchmark.Benchmark
import benchmark.Benchmark.faker
import core.DatabaseManager
import model.ConnectionModel
import model.DataType
import model.PolyData
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
                    DatabaseManager.insertConnection(
                        "${userCollection}_owns_$petCollection",
                        userCollection, userUuids.random(Benchmark.seed.asKotlinRandom()),
                        petCollection, petUuids.random(Benchmark.seed.asKotlinRandom()), generateConnectionData()
                    )
                }
            }
            


            currentCollectionSize = collectionSize
        }
    }
}