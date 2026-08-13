package benchmark

import core.DatabaseManager
import benchmark.environments.BenchEnvironmentDeepSubCollection
import net.datafaker.Faker
import java.util.Random
import kotlin.time.Duration.Companion.nanoseconds

object Benchmark {
    val seed = Random(55L)
    val faker = Faker(seed)

    const val RUN_ID = 10
    const val ITERATIONS = 1

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
            /*::BenchEnvironmentSimpleCollection,
            ::BenchEnvironmentSubCollection,*/
            ::BenchEnvironmentDeepSubCollection,
            /*::BenchEnvironmentVeryDeepSubCollection,
            ::BenchEnvironmentConnection,
            ::BenchEnvironmentDynamicData*/
        )
        val collectionSizes = listOf(100, 2000/*, 10000*/)

        val environments = buildList {
            for (envType in environmentTypes) {
                for (depth in collectionSizes) {
                    add(envType(RUN_ID, depth))
                }
            }
        }

        for (env in environments) {
            val measurements = env.bench(ITERATIONS)
            val csv = buildString {
                for (measurement in measurements) {
                    append(measurement.toCsvRow())
                    append("\n")
                }
            }
            /*File("C:\\Users\\flavi\\IdeaProjects\\polystore\\server\\docs\\data\\bench\\bench-data-raw.csv").appendText(
                csv
            )*/
        }
        println("bench done in ${(System.nanoTime() - start).nanoseconds} s")
    }
}