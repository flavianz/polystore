/**
 * How a poly DataType is stored natively, where GET /schema/mapping does not
 * say so itself. Mirrors server code — update together with it.
 */
import type { DataType } from '@/api/types';
import { assertNever } from '@/lib/assert-never';

/**
 * model/DataType.kt `toPostgresType()`. The mapping route reports this for
 * collection fields; connection data columns only carry the DataType, and use
 * the same function in `PostgresDriver.createConnection`.
 */
export function postgresType(type: DataType): string {
    switch (type) {
        case 'STRING':
            return 'TEXT';
        case 'INT':
            return 'INTEGER';
        case 'FLOAT':
            return 'FLOAT';
        case 'UUID':
            return 'UUID';
        case 'BOOLEAN':
            return 'BOOLEAN';
        case 'NULL':
            return 'NULL';
        default:
            return assertNever(type);
    }
}

/**
 * BSON type a MongoDB document stores, via the Java driver's default codecs:
 * Kotlin Float becomes a double, UUIDs are binary subtype 4
 * (`UuidRepresentation.STANDARD`, connection/MongoConnection.kt).
 */
export function mongoType(type: DataType): string {
    switch (type) {
        case 'STRING':
            return 'string';
        case 'INT':
            return 'int';
        case 'FLOAT':
            return 'double';
        case 'UUID':
            return 'UUID';
        case 'BOOLEAN':
            return 'bool';
        case 'NULL':
            return 'null';
        default:
            return assertNever(type);
    }
}

/**
 * Neo4jDriver `toNeo4j()`: UUIDs (including `_id`) are stored as strings;
 * everything else as the matching Cypher property type.
 */
export function neo4jType(type: DataType): string {
    switch (type) {
        case 'STRING':
        case 'UUID':
            return 'String';
        case 'INT':
            return 'Integer';
        case 'FLOAT':
            return 'Float';
        case 'BOOLEAN':
            return 'Boolean';
        case 'NULL':
            return 'null';
        default:
            return assertNever(type);
    }
}
