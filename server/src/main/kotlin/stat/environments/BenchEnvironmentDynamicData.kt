package ch.flavianz.stat.environments

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.DataType
import ch.flavianz.query.eq
import ch.flavianz.query.get
import ch.flavianz.query.isIn
import ch.flavianz.query.lt
import ch.flavianz.stat.BenchEnvironment
import ch.flavianz.stat.BenchFilterType
import ch.flavianz.stat.BenchResultType
import ch.flavianz.stat.Benchmark
import ch.flavianz.stat.BenchmarkQuery
import java.util.UUID
import kotlin.random.asKotlinRandom

class BenchEnvironmentDynamicData(
    override val runId: Int,
    override val collectionSize: Int,
) : BenchEnvironment(
    "dynamic data",
) {
    override fun benchQueries() = listOf(
        BenchmarkQuery(
            "dynamic collection all", 1, 0, BenchFilterType.None,
            get { collection("users") }, sizeLimit = 100, dynamicData = true
        ),
        BenchmarkQuery(
            "dynamic collection all only",
            1,
            0,
            BenchFilterType.None,
            get { collection("users", only = listOf("last", "child", "height")) },
            BenchResultType.Only,
            sizeLimit = 100, dynamicData = true
        ),
        BenchmarkQuery(
            "dynamic collection all range filter", 1, 1, BenchFilterType.NumberRange,
            get { collection("users", "height" lt 160) }, dynamicData = true
        ),
        BenchmarkQuery(
            "dynamic collection only range filter",
            1,
            1,
            BenchFilterType.NumberRange,
            get { collection("users", "height" lt 160, only = listOf("last", "child", "height")) },
            BenchResultType.Only, dynamicData = true
        ),
        BenchmarkQuery(
            "dynamic collection all string equality filter",
            1,
            1,
            BenchFilterType.Equality,
            get { collection("users", "last" eq filterLast.random(Benchmark.seed.asKotlinRandom())) },
            dynamicData = true
        ),
        BenchmarkQuery(
            "dynamic collection all string in list filter", 1, 1, BenchFilterType.ValueInList,
            get { collection("users", "last" isIn filterLast) }, dynamicData = true
        ),
        BenchmarkQuery(
            "dynamic collection all uuid equality filter", 1, 1, BenchFilterType.Equality,
            get { collection("users", "child" eq filterChildId) }, dynamicData = true
        ),
    )

    var filterLast = mutableListOf<String>()
    var filterChildId: UUID? = null

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
            val lastName = faker.name().firstName()
            filterLast.add(lastName)
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool(),
                        "last" to lastName,
                        "child" to UUID.randomUUID(),
                        "height" to faker.number().numberBetween(0, 160)
                    )
                )
            )
        }
        repeat(collectionSize - 20) {
            val id = UUID.randomUUID()
            filterChildId = id
            ids.add(
                DatabaseManager.insertDocument(
                    "users", mapOf(
                        "name" to faker.name().firstName(),
                        "age" to faker.number().numberBetween(0, 100),
                        "male" to faker.bool().bool(),
                        "last" to faker.name().firstName(),
                        "child" to id,
                        "height" to faker.number().numberBetween(160, 200)
                    )
                )
            )
        }
    }

    override fun cleanUp() {
        DatabaseManager.dropCollection("users")
    }
}