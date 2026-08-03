package ch.flavianz.stat.environments

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.DataType
import ch.flavianz.query.and
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

class BenchEnvironmentSimpleCollection(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "simple collection",
) {
    override val benchQueries: List<BenchmarkQuery>
        get() = listOf(
            BenchmarkQuery(
                "collection all", 1, 0, BenchFilterType.None,
                get { collection("users") }, 100
            ),
            BenchmarkQuery(
                "collection 1 range filter", 1, 1, BenchFilterType.NumberRange,
                get { collection("users", "age" gt 79) }),
            BenchmarkQuery(
                "collection 3 range filter", 1, 3, BenchFilterType.NumberRange,
                get { collection("users", ("age" lt 80) and (("age" gt 59) and ("male" eq true))) }),
            BenchmarkQuery(
                "collection get by id", 1, 1, BenchFilterType.GetDocByID,
                get { collection("users", "_id" eq ids.random(Benchmark.seed.asKotlinRandom())) }),
            BenchmarkQuery(
                "collection id in list", 1, 1, BenchFilterType.IdInList,
                get { collection("users", "_id" isIn ids.shuffled(Benchmark.seed.asKotlinRandom()).take(20)) }),
            BenchmarkQuery(
                "collection equality", 1, 1, BenchFilterType.Equality,
                get { collection("users", "age" eq 50) }),
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
        for (i in 0..<20) {
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 20),
                        "male" to false
                    )
                )
            )
        }
        for (i in 0..<10) {
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(80, 100),
                        "male" to true
                    )
                )
            )
        }
        for (i in 0..<(collectionSize - 20)) {
            if (i % 2000 == 0) println(i)
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(20, 80),
                        "male" to false
                    )
                )
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users")
    }
}