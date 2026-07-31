package ch.flavianz.stat

import ch.flavianz.core.DatabaseManager
import net.datafaker.Faker
import java.io.File
import java.util.Random
import kotlin.time.Duration.Companion.nanoseconds

object Benchmark {
    val seed = Random(55L)
    const val RUN_ID = 5
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
        for (env in listOf(
            //BenchEnvironmentSimpleCollection(RUN_ID, 100),
            //BenchEnvironmentSimpleCollection(RUN_ID, 5000),
            //BenchEnvironmentSimpleCollection(RUN_ID, 100_000),
            //BenchEnvironmentSubCollection(RUN_ID, 100),
            //BenchEnvironmentSubCollection(RUN_ID, 5000),
            //BenchEnvironmentSubCollection(RUN_ID, 100_000),
            //BenchEnvironmentDeepSubCollection(RUN_ID, 100),
            //BenchEnvironmentDeepSubCollection(RUN_ID, 5000),
            //BenchEnvironmentDeepSubCollection(RUN_ID, 100_000),
            //BenchEnvironmentConnection(RUN_ID, 100),
            BenchEnvironmentConnection(RUN_ID, 5000),
            //BenchEnvironmentConnection(RUN_ID, 100_000),
        )) {
            env.init()
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
            env.cleanUp()
        }
        println("bench done in ${(System.nanoTime() - start).nanoseconds} s")
    }
}