package benchmark

enum class BenchResultType {
    EntireDoc,
    Only;

    override fun toString(): String {
        return when (this) {
            EntireDoc -> "doc"
            Only -> "only"
        }
    }
}