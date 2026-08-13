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
import ch.flavianz.stat.BenchResultType
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
    override fun benchQueries() = listOf(
        BenchmarkQuery(
            "collection all", 1, 0, BenchFilterType.None,
            get { collection("users") }, sizeLimit = 100
        ),
        BenchmarkQuery(
            "collection all only", 1, 0, BenchFilterType.None,
            get { collection("users", only = "name") }, BenchResultType.Only, 100
        ),
        BenchmarkQuery(
            "collection 1 range filter", 1, 1, BenchFilterType.NumberRange,
            get { collection("users", "age" gt 79) }),
        BenchmarkQuery(
            "collection 3 range filter", 1, 3, BenchFilterType.NumberRange,
            get { collection("users", ("age" lt 90) and (("age" gt 39) and ("male" eq true))) }),
        BenchmarkQuery(
            "collection get by id", 1, 1, BenchFilterType.GetDocByID,
            get { collection("users", "_id" eq ids.random(Benchmark.seed.asKotlinRandom())) }),
        BenchmarkQuery(
            "collection id in list", 1, 1, BenchFilterType.IdInList,
            get { collection("users", "_id" isIn ids.shuffled(Benchmark.seed.asKotlinRandom()).take(20)) }),
        BenchmarkQuery(
            "collection id in list only",
            1,
            1,
            BenchFilterType.IdInList,
            get {
                collection(
                    "users",
                    "_id" isIn ids.shuffled(Benchmark.seed.asKotlinRandom()).take(20),
                    only = listOf("_id", "name")
                )
            },
            BenchResultType.Only
        ),
        BenchmarkQuery(
            "collection int equality", 1, 1, BenchFilterType.Equality,
            get { collection("users", "age" eq 50) }),
        BenchmarkQuery(
            "collection string equality", 1, 1, BenchFilterType.Equality,
            get { collection("users", "name" eq names.random(Benchmark.seed.asKotlinRandom())) }),
        BenchmarkQuery(
            "collection string equality multiple", 1, 1, BenchFilterType.Equality,
            get { collection("users", "name" eq multipleString) }),
        BenchmarkQuery(
            "collection string in list", 1, 1, BenchFilterType.ValueInList,
            get { collection("users", "name" isIn names.shuffled(Benchmark.seed.asKotlinRandom()).take(10)) }),
    )

    private val queue = mutableListOf<Map<String, Any?>>()
    val names = mutableListOf<String>()
    val multipleString: String = faker.name().firstName()

    private fun insert() {
        val n = queue.size
        for ((i, doc) in queue.shuffled(Benchmark.seed.asKotlinRandom()).withIndex()) {
            if (n > 2000 && i % 2000 == 0) println("inserted $i of $n")
            ids.add(
                DatabaseManager.insertDocument(
                    "users", doc
                )
            )
        }
    }

    val ids = mutableListOf<UUID>()
    override fun init() {
        DatabaseManager.createCollection(
            "users", mapOf(
                "name" to DataType.STRING,
                "age" to DataType.INT,
                "male" to DataType.BOOLEAN
            )
        )
        repeat(20) {
            val name = faker.name().firstName()
            names.add(name)
            queue.add(
                mapOf(
                    "name" to name,
                    "age" to faker.number().numberBetween(0, 20),
                    "male" to false
                )
            )
        }
        repeat(10) {
            queue.add(
                mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(80, 100),
                    "male" to true
                )
            )
        }
        repeat(10) {
            queue.add(
                mapOf(
                    "name" to faker.name().firstName(),
                    "age" to 50,
                    "male" to faker.bool().bool()
                )
            )
        }
        repeat(10) {
            queue.add(
                mapOf(
                    "name" to multipleString,
                    "age" to faker.number().numberBetween(20, 80),
                    "male" to false
                )
            )
        }
        repeat(collectionSize - 50) {
            queue.add(
                mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(20, 80),
                    "male" to false
                )
            )
        }
        insert()
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users")
    }
}