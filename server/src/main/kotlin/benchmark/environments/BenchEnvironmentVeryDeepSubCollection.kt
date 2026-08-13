package benchmark.environments

import core.DatabaseManager
import model.DataType
import query.eq
import ch.flavianz.query.get
import query.gt
import query.isIn
import benchmark.BenchEnvironment
import benchmark.BenchFilterType
import benchmark.BenchResultType
import benchmark.Benchmark
import benchmark.BenchmarkQuery
import java.util.UUID
import kotlin.random.asKotlinRandom

class BenchEnvironmentVeryDeepSubCollection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "very deep sub collection",
) {
    override fun benchQueries() = listOf(
        BenchmarkQuery(
            "very deep sub collection all only", 5, 0, BenchFilterType.None,
            get {
                collection("users", only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only, sizeLimit = 100
        ),
        BenchmarkQuery(
            "very deep sub collection all", 5, 0, BenchFilterType.None,
            get {
                collection("users")
                collection("children")
                collection("grandchildren")
                collection("great_grandchildren")
                collection("great_great_grandchildren")
            }, sizeLimit = 100
        ),
        BenchmarkQuery(
            "very deep sub collection filter all only", 5, 0, BenchFilterType.NumberRange,
            get {
                collection("users", "age" gt 79, only = "name")
                collection("children", "age" gt 79, only = "name")
                collection("grandchildren", "age" gt 79, only = "name")
                collection("great_grandchildren", "age" gt 79, only = "name")
                collection("great_great_grandchildren", "age" gt 79, only = "name")
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "very deep sub collection one doc by id only", 5, 0, BenchFilterType.GetDocByID,
            get {
                collection("users", "_id" eq oneId, only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "very deep sub collection id in list", 5, 0, BenchFilterType.IdInList,
            get {
                collection(
                    "users",
                    "_id" isIn userIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20),
                )
                collection("children")
                collection("grandchildren")
                collection("great_grandchildren")
                collection("great_great_grandchildren")
            }),
        BenchmarkQuery(
            "very deep sub collection id in list only", 5, 0, BenchFilterType.IdInList,
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
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "very deep sub collection int equality only", 5, 0, BenchFilterType.Equality,
            get {
                collection("users", "age" eq 50, only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "very deep sub collection int equality middle only", 5, 0, BenchFilterType.Equality,
            get {
                collection("users", only = "name")
                collection("children", only = "name")
                collection("grandchildren", "name" eq middleName, only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only
        ),

        BenchmarkQuery(
            "very deep sub collection string equality only", 5, 0, BenchFilterType.Equality,
            get {
                collection("users", "name" eq names.random(Benchmark.seed.asKotlinRandom()), only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "very deep sub collection string equality multiple only", 5, 0, BenchFilterType.Equality,
            get {
                collection("users", "name" eq multipleString, only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "very deep sub collection string in list only", 5, 0, BenchFilterType.ValueInList,
            get {
                collection("users", "name" isIn names.shuffled(Benchmark.seed.asKotlinRandom()).take(10), only = "name")
                collection("children", only = "name")
                collection("grandchildren", only = "name")
                collection("great_grandchildren", only = "name")
                collection("great_great_grandchildren", only = "name")
            }, BenchResultType.Only
        ),
    )

    val userIds = mutableListOf<UUID>()
    val names = mutableListOf<String>()
    var oneId: UUID? = null
    val multipleString = faker.name().firstName()
    val middleName = faker.name().firstName()

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

        val childIds = mutableListOf<UUID>()
        val grandchildIds = mutableListOf<UUID>()
        val greatGrandchildIds = mutableListOf<UUID>()

        repeat(20) {
            val name = faker.name().firstName()
            names.add(name)
            val id = DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to name,
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                )
            )
            if (it == 0) {
                oneId = id
            }
            userIds.add(id)
        }

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
        for (userId in userIds) {
            childIds.add(
                DatabaseManager.insertDocument(
                    "children", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to faker.bool().bool()
                    ), userId
                )
            )
        }
        for (userId in childIds) {
            grandchildIds.add(
                DatabaseManager.insertDocument(
                    "grandchildren", mapOf(
                        "name" to middleName,
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to faker.bool().bool()
                    ), userId
                )
            )
        }
        repeat(30) {
            greatGrandchildIds.add(
                DatabaseManager.insertDocument(
                    "great_grandchildren", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to faker.bool().bool()
                    ), grandchildIds.random(Benchmark.seed.asKotlinRandom())
                )
            )
        }
        repeat(30) {
            DatabaseManager.insertDocument(
                "great_great_grandchildren", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), greatGrandchildIds.random(Benchmark.seed.asKotlinRandom())
            )
        }

        repeat(10) {
            userIds.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to multipleString,
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }

        repeat(collectionSize - 40) {
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

        repeat(collectionSize - 30) {
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

        repeat(collectionSize - 30) {
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

        repeat(collectionSize - 30) {
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

        repeat(collectionSize - 30) {
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