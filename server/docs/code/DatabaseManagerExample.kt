object DatabaseManager {
    private var collections = mutableMapOf<String, CollectionModel>()
    private var connections = mutableMapOf<String, ConnectionModel>()

    fun insertDocument(
        collectionName: String,
        data: PolyData,
        parentDocUuid: UUID? = null
    ): UUID {
        check(existsCollection(collectionName))
            { "collection $collectionName does not exist" }
        val collectionModel = getCollectionModel(collectionName)
        check(dataContainsAllRequiredFields(data, collectionModel.schema))
            { "document data does not contain all required fields of collection $collectionName" }
        checkIsDataValid(data)

        if (collectionModel.hasParentCollection()) {
            check(parentDocUuid != null)
                { "collection $collectionName has a parent collection, specify a parent document" }
        } else {
            check(parentDocUuid == null)
                { "collection $collectionName does not have a parent collection" }
        }

        val documentUuid = UUID.randomUUID()

        DriverManager.execute {
            (DatabaseDriver::insertDocument)
                (collectionModel, documentUuid, data, parentDocUuid)
        }
        return documentUuid
    }
}