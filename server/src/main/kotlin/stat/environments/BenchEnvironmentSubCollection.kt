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

class BenchEnvironmentSubCollection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "sub collection",
) {
    override fun benchQueries() = listOf(
        BenchmarkQuery(
            "sub collection collect all", 2, 0, BenchFilterType.None,
            get {
                collection("users")
                collection("children")
            }, sizeLimit = 100
        ),
        BenchmarkQuery(
            "sub collection collect all only", 2, 0, BenchFilterType.None,
            get {
                collection("users", only = "name")
                collection("children", only = "name")
            }, BenchResultType.Only, sizeLimit = 100
        ),
        BenchmarkQuery(
            "sub collection child range filter", 2, 1, BenchFilterType.NumberRange,
            get {
                collection("users")
                collection("children", "age" gt 79)
            }),
        BenchmarkQuery(
            "sub collection parent range filter", 2, 1, BenchFilterType.NumberRange,
            get {
                collection("users", "age" gt 79)
                collection("children")
            }),
        BenchmarkQuery(
            "sub collection child and parent range filter", 2, 2, BenchFilterType.NumberRange,
            get {
                collection("users", "age" gt 79)
                collection("children", "age" gt 79)
            }),
        BenchmarkQuery(
            "sub collection get one by id", 2, 1, BenchFilterType.GetDocByID,
            get {
                collection("users", "_id" eq parentIds.random(Benchmark.seed.asKotlinRandom()))
                collection("children")
            }),
        BenchmarkQuery(
            "sub collection id in list", 2, 1, BenchFilterType.IdInList,
            get {
                collection("users", "_id" isIn parentIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20))
                collection("children")
            }),
        BenchmarkQuery(
            "sub collection id in list only", 2, 1, BenchFilterType.IdInList,
            get {
                collection(
                    "users",
                    "_id" isIn parentIds.shuffled(Benchmark.seed.asKotlinRandom()).take(20),
                    only = listOf("_id", "name")
                )
                collection("children")
            }, BenchResultType.Only
        ),
        BenchmarkQuery(
            "sub collection equality", 2, 1, BenchFilterType.Equality,
            get {
                collection("users", "age" eq 50)
                collection("children")
            }),
        BenchmarkQuery(
            "sub collection string equality", 2, 1, BenchFilterType.Equality,
            get {
                collection("users", "name" eq names.random(Benchmark.seed.asKotlinRandom()))
                collection("children")
            }),
        BenchmarkQuery(
            "sub collection string in list", 2, 1, BenchFilterType.ValueInList,
            get {
                collection("users", "name" isIn names.shuffled(Benchmark.seed.asKotlinRandom()).take(10))
                collection("children")
            }),
    )

    val ids = mutableListOf<UUID>()
    val names = mutableListOf<String>()
    var parentIds = mutableListOf<UUID>()

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
        repeat(20) {
            val name = faker.name().firstName()
            names.add(name)
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to name,
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }
        repeat(20) {
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), ids.random(Benchmark.seed.asKotlinRandom())
            )
        }
        repeat(10) {
            val id = DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to 50,
                    "male" to faker.bool().bool()
                )
            )
            ids.add(
                id
            )
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), id
            )
        }
        repeat(collectionSize - 30) {
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 80),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }
        repeat(10) {
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to faker.bool().bool()
                ), ids.random(Benchmark.seed.asKotlinRandom())
            )
        }
        repeat(collectionSize - 20) {
            val id = ids.random(Benchmark.seed.asKotlinRandom())
            if (parentIds.size < 50) {
                parentIds.add(id)
            }
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 80),
                    "male" to faker.bool().bool()
                ), id
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }
}