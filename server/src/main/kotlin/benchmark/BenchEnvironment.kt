package ch.flavianz.stat

import ch.flavianz.driver.DriverManager

abstract class BenchEnvironment(val benchEnvName: String) {
    abstract val runId: Int
    abstract val collectionSize: Int
    abstract fun benchQueries(): List<BenchmarkQuery>
    protected abstract fun init()
    protected abstract fun cleanUp()

    val faker = Benchmark.faker
    val durationMeasurements = mutableListOf<DurationMeasurement>()

    fun bench(iterations: Int): List<DurationMeasurement> {
        println("init bench $benchEnvName")
        init()
        val benchQueries = benchQueries()
        for ((index, query) in benchQueries.withIndex()) {
            if (query.sizeLimit == null || query.sizeLimit >= collectionSize) {
                println("benching ${query.queryShape} ($index/${benchQueries.size})")
                durationMeasurements.addAll(
                    DriverManager.benchmarkGet(
                        iterations,
                        runId,
                        collectionSize, query
                    )
                )
            } else {
                println("skipping bench ${query.queryShape} because of size (${index + 1}/${benchQueries.size})")
            }
        }
        println("clean bench $benchEnvName")
        cleanUp()
        return durationMeasurements
    }
}