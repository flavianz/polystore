package driver

import benchmark.regression.QueryProperties
import benchmark.regression.parseQueryProperties
import query.DriverType
import query.GetQuery
import kotlin.math.expm1
import kotlin.math.ln1p

object RegressionModel {
    private data class DriverModel(
        val intercept: Double,
        val coefficients: Map<String, Double>,
        val log1pFeatures: Set<String> = emptySet(),
        val log1pTarget: Boolean = false,
    )

    private val POSTGRES_MODEL = DriverModel(
        intercept = -612.9445900504884,
        coefficients = mapOf(
            "collectionSize" to 0.31754219282885016,
            "singleCollectionSegmentCount" to -137.70473926528322,
            "pairCollectionSegmentCount" to -99.77820048677319,
            "connectionSegmentCount" to 696.7513124798938,
            "multiQueryCount" to 86.356984580267,
            "rootIdFilterCount" to 197.00579800665454,
            "rootValueInListFilterCount" to 462.2176599250427,
            "rootEqualityFilterCount" to 393.86655512373056,
            "rootNumberRangeFilterCount" to 551.0542960375045,
            "nestedIdFilterCount" to 4.907151013703957,
            "nestedValueInListFilterCount" to 58.534818674598085,
            "nestedEqualityFilterCount" to -35.6799366851454,
            "nestedNumberRangeFilterCount" to 60.24756482687914,
            "firstFilterDepth" to 1757.9381170025417,
            "onlyResultFraction" to -5.986236733683374,
            "dynamicFilterFraction" to 858.7430348895912,
            "dynamicResultFraction" to 212.2412195957651,
            "connectionSegmentCount_x_firstFilterDepth" to -573.06144170776,
        ),
    )

    private val MONGO_MODEL = DriverModel(
        intercept = 6.398720688975065,
        coefficients = mapOf(
            "collectionSize" to 0.16045539470860995,
            "singleCollectionSegmentCount" to -0.5119699788547685,
            "pairCollectionSegmentCount" to -0.8545654200813578,
            "connectionSegmentCount" to -0.6974301821459988,
            "multiQueryCount" to -0.06421766364390034,
            "rootIdFilterCount" to 0.5207292876215656,
            "rootValueInListFilterCount" to 0.718001180131534,
            "rootEqualityFilterCount" to 0.6468856396189052,
            "rootNumberRangeFilterCount" to 0.7610430945222388,
            "nestedIdFilterCount" to 0.4475723500111899,
            "nestedValueInListFilterCount" to 0.5032339971574149,
            "nestedEqualityFilterCount" to 0.510876201082142,
            "nestedNumberRangeFilterCount" to 0.5289012434196722,
            "firstFilterDepth" to 1.4646449251646436,
            "onlyResultFraction" to 0.043397626528785704,
            "dynamicFilterFraction" to 0.8117998489946504,
            "dynamicResultFraction" to 0.14254271365814178,
            "connectionSegmentCount_x_firstFilterDepth" to -0.4333560395087744,
        ),
        log1pFeatures = setOf("collectionSize"),
        log1pTarget = true,
    )

    private val NEO4J_MODEL = DriverModel(
        intercept = 6.945399362333307,
        coefficients = mapOf(
            "collectionSize" to 0.058427344485983475,
            "singleCollectionSegmentCount" to -0.03394905535625201,
            "pairCollectionSegmentCount" to -0.08449539625198829,
            "connectionSegmentCount" to -0.06491627734395879,
            "multiQueryCount" to -0.006655857055693027,
            "rootIdFilterCount" to 0.017952878689169106,
            "rootValueInListFilterCount" to 0.08840235299352923,
            "rootEqualityFilterCount" to 0.05573533939950407,
            "rootNumberRangeFilterCount" to 0.09438175736398689,
            "nestedIdFilterCount" to 0.029242504453485998,
            "nestedValueInListFilterCount" to 0.06463075084466391,
            "nestedEqualityFilterCount" to 0.07080292877557695,
            "nestedNumberRangeFilterCount" to 0.0884520384391766,
            "firstFilterDepth" to 0.4034774705603371,
            "onlyResultFraction" to -0.021941127275187808,
            "dynamicFilterFraction" to 0.3196402649880116,
            "dynamicResultFraction" to 0.020456165167430693,
            "connectionSegmentCount_x_firstFilterDepth" to -0.15126145247574335,
        ),
        log1pFeatures = setOf("collectionSize"),
        log1pTarget = true,
    )

    private val MODELS: Map<DriverType, DriverModel> = mapOf(
        DriverType.Postgres to POSTGRES_MODEL,
        DriverType.Mongo to MONGO_MODEL,
        DriverType.Neo4j to NEO4J_MODEL,
    )

    private fun predict(model: DriverModel, features: Map<String, Double>): Double {
        var total = model.intercept
        for ((feature, coefficient) in model.coefficients) {
            val raw = features[feature]
                ?: feature.split("_x_", limit = 2).let { parts ->
                    if (parts.size == 2) {
                        val a = features[parts[0]]
                        val b = features[parts[1]]
                        if (a != null && b != null) a * b else null
                    } else null
                }
                ?: throw IllegalStateException("Feature '$feature' not available .")
            val value = if (feature in model.log1pFeatures) ln1p(raw) else raw
            total += coefficient * value
        }
        return if (model.log1pTarget) expm1(total) else total
    }

    private fun toFeatureMap(p: QueryProperties, collectionSize: Int): Map<String, Double> = mapOf(
        "collectionSize" to collectionSize.toDouble(),
        "singleCollectionSegmentCount" to p.singleCollectionSegmentCount.toDouble(),
        "pairCollectionSegmentCount" to p.pairCollectionSegmentCount.toDouble(),
        "connectionSegmentCount" to p.connectionSegmentCount.toDouble(),
        "multiQueryCount" to p.multiQueryCount.toDouble(),
        "rootIdFilterCount" to p.rootFilterCounts.idFilterCount.toDouble(),
        "rootValueInListFilterCount" to p.rootFilterCounts.valueInListFilterCount.toDouble(),
        "rootEqualityFilterCount" to p.rootFilterCounts.equalityFilterCount.toDouble(),
        "rootNumberRangeFilterCount" to p.rootFilterCounts.numberRangeFilterCount.toDouble(),
        "nestedIdFilterCount" to p.nestedFilterCounts.idFilterCount.toDouble(),
        "nestedValueInListFilterCount" to p.nestedFilterCounts.valueInListFilterCount.toDouble(),
        "nestedEqualityFilterCount" to p.nestedFilterCounts.equalityFilterCount.toDouble(),
        "nestedNumberRangeFilterCount" to p.nestedFilterCounts.numberRangeFilterCount.toDouble(),
        "firstFilterDepth" to p.firstFilterDepth.toDouble(),
        "onlyResultFraction" to p.onlyResultFraction,
        "dynamicFilterFraction" to p.dynamicFilterFraction,
        "dynamicResultFraction" to p.dynamicResultFraction,
    )

    fun calculateFastestDriverRegression(query: GetQuery, availableDrivers: Set<DriverType>): DriverType {
        val properties = parseQueryProperties(query)
        val features = toFeatureMap(properties, collectionSize = 0)

        val bestDriver = MODELS.filterKeys { availableDrivers.contains(it) }
            .mapValues { (_, model) -> predict(model, features) }
            .minByOrNull { it.value }
            ?.key
            ?: throw IllegalStateException("no model prediction available")

        return bestDriver
    }
}