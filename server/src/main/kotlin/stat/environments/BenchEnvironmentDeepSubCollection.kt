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

class BenchEnvironmentDeepSubCollection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "deep sub collection",
) {
    override val benchQueries: List<BenchmarkQuery>
        get() = listOf(
            BenchmarkQuery(
                "deep sub collection collect all", 3, 0, BenchFilterType.None,
                get {
                    collection("users")
                    collection("children")
                    collection("grandchildren")
                }, 100
            ),
            BenchmarkQuery(
                "sub collection grand child range filter", 3, 1, BenchFilterType.NumberRange,
                get {
                    collection("users")
                    collection("children")
                    collection("grandchildren", "age" gt 79)

                }),
            BenchmarkQuery(
                "sub collection parent range filter", 3, 1, BenchFilterType.NumberRange,
                get {
                    collection("users", "age" gt 79)
                    collection("children")
                    collection("grandchildren")
                }),
            BenchmarkQuery(
                "sub collection child range filter", 3, 1, BenchFilterType.NumberRange,
                get {
                    collection("users")
                    collection("children", "age" gt 79)
                    collection("grandchildren")
                }),
            BenchmarkQuery(
                "sub collection child, grandchild and parent range filter", 3, 3, BenchFilterType.NumberRange,
                get {
                    collection("users", "age" gt 79)
                    collection("children", "age" gt 79)
                    collection("grandchildren", "age" gt 79)
                }),
            BenchmarkQuery(
                "sub collection get one by id", 2, 1, BenchFilterType.GetDocByID,
                get {
                    collection("users", "_id" eq userIds.random(Benchmark.seed.asKotlinRandom()))
                    collection("children")
                    collection("grandchildren")
                }),
            BenchmarkQuery(
                "sub collection id in list", 2, 1, BenchFilterType.IdInList,
                get {
                    collection("users", "_id" isIn userIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20))
                    collection("children")
                    collection("grandchildren")
                }),
            BenchmarkQuery(
                "sub collection equality", 2, 1, BenchFilterType.Equality,
                get {
                    collection("users", "age" eq 50)
                    collection("children")
                    collection("grandchildren")
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

        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 100),
                    "male" to faker.bool().bool()
                ), childIds.random(Benchmark.seed.asKotlinRandom())
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }
}