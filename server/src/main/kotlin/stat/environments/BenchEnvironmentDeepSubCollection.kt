package ch.flavianz.stat.environments

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.DataType
import ch.flavianz.query.eq
import ch.flavianz.query.get
import ch.flavianz.query.gt
import ch.flavianz.query.isIn
import ch.flavianz.stat.BenchEnvironment
import ch.flavianz.stat.BenchFilterType
import ch.flavianz.stat.BenchResultType
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
    override fun benchQueries() = listOf(
        BenchmarkQuery(
            "deep sub collection collect all", 3, 0, BenchFilterType.None,
            get {
                collection("users")
                collection("children")
                collection("grandchildren")
            }, sizeLimit = 100
        ),
        BenchmarkQuery(
            "deep sub collection collect all only", 3, 0, BenchFilterType.None,
            get {
                collection("users", only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
            }, BenchResultType.SingleField, 100
        ),
        BenchmarkQuery(
            "deep sub collection grand child range filter", 3, 1, BenchFilterType.NumberRange,
            get {
                collection("users")
                collection("children")
                collection("grandchildren", "age" gt 79)

            }),
        BenchmarkQuery(
            "deep sub collection parent range filter", 3, 1, BenchFilterType.NumberRange,
            get {
                collection("users", "age" gt 79)
                collection("children")
                collection("grandchildren")
            }),
        BenchmarkQuery(
            "deep sub collection child range filter", 3, 1, BenchFilterType.NumberRange,
            get {
                collection("users")
                collection("children", "age" gt 79)
                collection("grandchildren")
            }),
        BenchmarkQuery(
            "deep sub collection child, grandchild and parent range filter", 3, 3, BenchFilterType.NumberRange,
            get {
                collection("users", "age" gt 79)
                collection("children", "age" gt 79)
                collection("grandchildren", "age" gt 79)
            }),
        BenchmarkQuery(
            "deep sub collection get one by id", 2, 1, BenchFilterType.GetDocByID,
            get {
                collection("users", "_id" eq oneId)
                collection("children")
                collection("grandchildren")
            }),
        BenchmarkQuery(
            "deep sub collection id in list", 2, 1, BenchFilterType.IdInList,
            get {
                collection("users", "_id" isIn userIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20))
                collection("children")
                collection("grandchildren")
            }),
        BenchmarkQuery(
            "deep sub collection id in list only", 2, 1, BenchFilterType.IdInList,
            get {
                collection("users", "_id" isIn userIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20), "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
            }),
        BenchmarkQuery(
            "deep sub collection equality", 2, 1, BenchFilterType.Equality,
            get {
                collection("users", "age" eq 50)
                collection("children")
                collection("grandchildren")
            }),
    )

    val userIds = mutableListOf<UUID>()

    var oneId: UUID? = null
    var subId: UUID? = null

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

        val childIds = mutableListOf<UUID>()

        repeat(20) {
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }
        repeat(10) {
            val id = userIds.random(Benchmark.seed.asKotlinRandom())
            if (it == 0) {
                oneId = id
            }
            val docId = DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), id
            )
            if (it == 0) {
                oneId = id
                subId = docId
            }
            childIds.add(
                docId
            )
        }
        repeat(10) {
            val id = if (it == 0) subId else childIds.random(Benchmark.seed.asKotlinRandom())
            DatabaseManager.insertDocument(
                "grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), id
            )
        }
        repeat(collectionSize - 50) {
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 80),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }
        repeat(20) {
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

        repeat(20) {
            childIds.add(
                DatabaseManager.insertDocument(
                    "children", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to faker.bool().bool()
                    ), userIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }
        repeat(collectionSize - 30) {
            childIds.add(
                DatabaseManager.insertDocument(
                    "children", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 80),
                        "male" to faker.bool().bool()
                    ), userIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }

        repeat(20) {
            DatabaseManager.insertDocument(
                "grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), childIds.random(Benchmark.seed.asKotlinRandom())
            )
        }
        repeat(collectionSize - 30) {
            DatabaseManager.insertDocument(
                "grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 80),
                    "male" to faker.bool().bool()
                ), childIds.random(Benchmark.seed.asKotlinRandom())
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }
}