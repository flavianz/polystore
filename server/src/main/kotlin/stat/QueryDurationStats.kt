package ch.flavianz.stat

import ch.flavianz.query.DriverType
import kotlin.time.Duration

enum class MeasurementPhase {
    Build,
    Exec;

    override fun toString(): String {
        return when (this) {
            Build -> "build"
            Exec -> "exec"
        }
    }
}

data class DurationMeasurement(
    val runId: Int,
    val queryShape: String,
    val driver: DriverType,
    val collectionSize: Int,
    val depth: Int,
    val filterCount: Int,
    val phase: MeasurementPhase,
    val iteration: Int,
    val duration: Duration
) {
    fun toCsvRow(): String {
        return "$runId;$queryShape;$driver;$collectionSize;$depth;$filterCount;$phase;$iteration;${duration.inWholeMicroseconds}"
    }
}