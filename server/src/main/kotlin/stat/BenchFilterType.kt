package ch.flavianz.stat

enum class BenchFilterType {
    GetDocByID,
    IdInList,
    ValueInList,
    NumberRange,
    Equality,
    None;

    override fun toString(): String {
        return when (this) {
            GetDocByID -> "docById"
            IdInList -> "idInList"
            ValueInList -> "valueInList"
            NumberRange -> "numberRange"
            Equality -> "equality"
            None -> "none"
        }
    }
}