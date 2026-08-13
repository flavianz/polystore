package ch.flavianz

import ch.flavianz.core.DatabaseManager
import ch.flavianz.model.DataType
import ch.flavianz.stat.Benchmark
import ch.flavianz.stat.Benchmark.faker
import java.util.UUID
import kotlin.random.asKotlinRandom

fun initExampleOne() {
    DatabaseManager.dropCollection("users", true)
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

    val ids = mutableListOf<UUID>()

    repeat(100) {
        ids.add(
            DatabaseManager.insertDocument(
                "users", mapOf(
                    "name" to faker.name().firstName(),
                    "age" to faker.number().numberBetween(0, 100),
                    "male" to faker.bool().bool()
                )
            )
        )
        DatabaseManager.insertDocument(
            "children", mapOf(
                "name" to faker.name().firstName(),
                "age" to faker.number().numberBetween(0, 100),
                "male" to faker.bool().bool()
            ), ids.random(Benchmark.seed.asKotlinRandom())
        )
    }
}