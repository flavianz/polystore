package benchmark.regression

import query.DriverType

data class RegressionBenchMeasurement(
    val driver: DriverType,
    val collectionSize: Int,
    val singleCollectionSegmentCount: Int,
    val pairCollectionSegmentCount: Int,
    val connectionSegmentCount: Int,
    val multiQueryCount: Int,
    val rootFilterCounts: FilterCounts,
    val nestedFilterCounts: FilterCounts,
    val firstFilterDepth: Int,
    val onlyResultFraction: Double,
    val dynamicFilterFraction: Double,
    val dynamicResultFraction: Double
)

data class FilterCounts(
    val idFilterCount: Int,
    val valueInListFilterCount: Int,
    val equalityFilterCount: Int,
    val numberRangeFilterCount: Int,

    )