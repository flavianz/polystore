package ch.flavianz.stat.environments

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.ConnectionModel
import ch.flavianz.model.DataType
import ch.flavianz.query.eq
import ch.flavianz.query.get
import ch.flavianz.query.gt
import ch.flavianz.query.isIn
import ch.flavianz.query.lt
import ch.flavianz.stat.BenchEnvironment
import ch.flavianz.stat.BenchFilterType
import ch.flavianz.stat.Benchmark
import ch.flavianz.stat.BenchmarkQuery
import java.util.UUID
import kotlin.random.asKotlinRandom

class BenchEnvironmentConnection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "connection",
) {
    override fun benchQueries() = listOf(
        BenchmarkQuery(
            "connection all", 3, 0, BenchFilterType.None,
            get {
                collection("users")
                connection("practices", "hobbies")
            }, 100
        ),
        BenchmarkQuery(
            "connection filter on edge property", 3, 1, BenchFilterType.NumberRange,
            get {
                collection("users")
                connection("practices", "hobbies", connectionCondition = "years_active" gt 10)
            }),
        BenchmarkQuery(
            "connection filter on far node", 3, 1, BenchFilterType.Equality,
            get {
                collection("users")
                connection(
                    "practices",
                    "hobbies",
                    collectionCondition = "name" eq hobbies.random(Benchmark.seed.asKotlinRandom())
                )
            }),
        BenchmarkQuery(
            "connection filter near node and edge", 3, 2, BenchFilterType.NumberRange,
            get {
                collection("users", "age" lt 80)
                connection("practices", "hobbies", connectionCondition = "years_active" gt 10)
            }),
        BenchmarkQuery(
            "connection get one by id", 3, 1, BenchFilterType.GetDocByID,
            get {
                collection("users", "_id" eq userIds.random(Benchmark.seed.asKotlinRandom()))
                connection("practices", "hobbies")
            }),
        BenchmarkQuery(
            "connection id in list", 3, 1, BenchFilterType.IdInList,
            get {
                collection("users", "_id" isIn userIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20))
                connection("practices", "hobbies")
            }),
        BenchmarkQuery(
            "connection equality", 3, 1, BenchFilterType.Equality,
            get {
                collection("users", "age" eq 50)
                connection("practices", "hobbies")
            }),
    )

    val userIds = mutableListOf<UUID>()
    val hobbies = mutableListOf<String>()

    override fun init() {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        DatabaseManager.createCollection(
            "hobbies", mapOf(
                "name" to DataType.STRING
            )
        )
        DatabaseManager.createConnection(
            ConnectionModel(
                "practices", "users", "hobbies",
                mapOf(
                    "years_active" to DataType.INT
                ),
            )
        )

        val hobbyIds = mutableListOf<UUID>()

        repeat(10) {
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to 50,
                        "male" to faker.bool().bool()
                    )
                )
            )
        }

        repeat(collectionSize - 10) {
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }

        repeat(collectionSize / 10) {
            val hobby = faker.hobby().activity()
            hobbies.add(hobby)
            hobbyIds.add(
                DatabaseManager.insertDocument(
                    "hobbies", mapOf(
                        "name" to hobby
                    )
                )
            )
        }


        val rng = Benchmark.seed.asKotlinRandom()
        for (userId in userIds.shuffled(rng).take(10)) {
            val connectionCount = rng.nextInt(1, 4)
            repeat(connectionCount) {
                DatabaseManager.insertConnection(
                    "practices",
                    "users",
                    userId, "hobbies",
                    hobbyIds.random(rng),
                    mapOf("years_active" to faker.number().numberBetween(10, 30))
                )
            }
        }
        for (userId in userIds) {
            val connectionCount = rng.nextInt(1, 4)
            repeat(connectionCount) {
                DatabaseManager.insertConnection(
                    "practices",
                    "users",
                    userId, "hobbies",
                    hobbyIds.random(rng),
                    mapOf("years_active" to faker.number().numberBetween(0, 10))
                )
            }
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropConnection("practices")
        DatabaseManager.dropCollection("users", true)
        DatabaseManager.dropCollection("hobbies", true)
    }
}