package ch.flavianz.stat

import ch.flavianz.core.DatabaseManager
import ch.flavianz.stat.environments.BenchEnvironmentSimpleCollection
import net.datafaker.Faker
import java.io.File
import java.util.Random
import kotlin.time.Duration.Companion.nanoseconds

object Benchmark {
    val seed = Random(55L)
    const val RUN_ID = 6
    val faker = Faker(seed)

    fun startBenchmark() {
        val start = System.nanoTime()
        for (connection in DatabaseManager.listConnections()) {
            DatabaseManager.dropConnection(connection.name)
        }
        for (collection in DatabaseManager.listCollections()) {
            if (DatabaseManager.existsCollection(collection.name)) {
                DatabaseManager.dropCollection(collection.name, true)
            }
        }

        val environmentTypes = listOf(
            ::BenchEnvironmentSimpleCollection,
            /*::BenchEnvironmentSubCollection,
            ::BenchEnvironmentDeepSubCollection,
            ::BenchEnvironmentVeryDeepSubCollection,
            ::BenchEnvironmentConnection*/
        )
        val depths = listOf(100)

        val environments = buildList {
            for (envType in environmentTypes) {
                for (depth in depths) {
                    add(envType(RUN_ID, depth))
                }
            }
        }

        for (env in environments) {
            val measurements = env.bench()
            val csv = buildString {
                for (measurement in measurements) {
                    append(measurement.toCsvRow())
                    append("\n")
                }
            }
            File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\bench\\bench-data-raw.csv").appendText(
                csv
            )
        }
        println("bench done in ${(System.nanoTime() - start).nanoseconds} s")
    }
}