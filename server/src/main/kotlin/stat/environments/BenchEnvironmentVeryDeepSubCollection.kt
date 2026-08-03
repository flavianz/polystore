package ch.flavianz.stat.environments

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.DataType
import ch.flavianz.query.eq
import ch.flavianz.query.get
import ch.flavianz.query.gt
import ch.flavianz.query.isIn
import ch.flavianz.stat.BenchEnvironment
import ch.flavianz.stat.BenchFilterType
import ch.flavianz.stat.Benchmark
import ch.flavianz.stat.BenchmarkQuery
import java.util.UUID
import kotlin.random.asKotlinRandom

class BenchEnvironmentVeryDeepSubCollection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "very deep sub collection",
) {
    override val benchQueries: List<BenchmarkQuery>
        get() = listOf(
            BenchmarkQuery(
                "very deep sub collection all", 5, 0, BenchFilterType.None,
                get {
                    collection("users", only = "name")
                    collection("children", only = "name")
                    collection("grandchildren", only = "name")
                    collection("great_grandchildren", only = "name")
                    collection("great_great_grandchildren", only = "name")
                }, 100
            ),
            BenchmarkQuery(
                "very deep sub collection filter all", 5, 0, BenchFilterType.NumberRange,
                get {
                    collection("users", "age" gt 79, only = "name")
                    collection("children", "age" gt 79, only = "name")
                    collection("grandchildren", "age" gt 79, only = "name")
                    collection("great_grandchildren", "age" gt 79, only = "name")
                    collection("great_great_grandchildren", "age" gt 79, only = "name")
                }),
            BenchmarkQuery(
                "very deep sub collection one doc by id", 5, 0, BenchFilterType.GetDocByID,
                get {
                    collection("users", "_id" eq userIds.random(Benchmark.seed.asKotlinRandom()), only = "name")
                    collection("children", only = "name")
                    collection("grandchildren", only = "name")
                    collection("great_grandchildren", only = "name")
                    collection("great_great_grandchildren", only = "name")
                }),
            BenchmarkQuery(
                "very deep sub collection id in list", 5, 0, BenchFilterType.IdInList,
                get {
                    collection(
                        "users",
                        "_id" isIn userIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20),
                        only = "name"
                    )
                    collection("children", only = "name")
                    collection("grandchildren", only = "name")
                    collection("great_grandchildren", only = "name")
                    collection("great_great_grandchildren", only = "name")
                }),
            BenchmarkQuery(
                "very deep sub collection equality", 5, 0, BenchFilterType.Equality,
                get {
                    collection("users", "age" eq 50, only = "name")
                    collection("children", only = "name")
                    collection("grandchildren", only = "name")
                    collection("great_grandchildren", only = "name")
                    collection("great_great_grandchildren", only = "name")
                }),
        )

    val userIds = mutableListOf<UUID>()

    override fun init() {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        DatabaseManager.createCollection(
            "children", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "users"
        )
        DatabaseManager.createCollection(
            "grandchildren", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "children"
        )
        DatabaseManager.createCollection(
            "great_grandchildren", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "grandchildren"
        )
        DatabaseManager.createCollection(
            "great_great_grandchildren", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            ), "great_grandchildren"
        )

        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
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

        val childIds = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            childIds.add(
                DatabaseManager.insertDocument(
                    "children", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    ), userIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }
        val grandchildIds = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            grandchildIds.add(
                DatabaseManager.insertDocument(
                    "grandchildren", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    ), childIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }

        val greatGrandchildIds = mutableListOf<UUID>()
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            greatGrandchildIds.add(
                DatabaseManager.insertDocument(
                    "great_grandchildren", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    ), grandchildIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }

        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "great_great_grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 100),
                    "male" to faker.bool().bool()
                ), greatGrandchildIds.random(Benchmark.seed.asKotlinRandom())
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }
}