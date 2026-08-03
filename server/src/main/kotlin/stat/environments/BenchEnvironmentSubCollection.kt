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

class BenchEnvironmentSubCollection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "sub collection",
) {
    override val benchQueries: List<BenchmarkQuery>
        get() = listOf(
            BenchmarkQuery(
                "sub collection collect all", 2, 0, BenchFilterType.None,
                get {
                    collection("users")
                    collection("children")
                }, 100
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
                    collection("users", "_id" eq ids.random(Benchmark.seed.asKotlinRandom()))
                    collection("children")
                }),
            BenchmarkQuery(
                "sub collection id in list", 2, 1, BenchFilterType.GetDocByID,
                get {
                    collection("users", "_id" isIn ids.shuffled(Benchmark.seed.asKotlinRandom()).take(20))
                    collection("children")
                }),
            BenchmarkQuery(
                "sub collection equality", 2, 1, BenchFilterType.Equality,
                get {
                    collection("users", "age" eq 50)
                    collection("children")
                }),
        )

    val ids = mutableListOf<UUID>()

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
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool()
                    )
                )
            )
        }
        for (i in 0..<collectionSize) {
            if (i % 2000 == 0) println(i)
            DatabaseManager.insertDocument(
                "children", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 100),
                    "male" to faker.bool().bool()
                ), ids.random(Benchmark.seed.asKotlinRandom())
            )

        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users", true)
    }
}