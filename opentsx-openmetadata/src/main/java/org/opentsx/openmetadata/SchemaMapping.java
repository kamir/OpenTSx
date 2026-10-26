package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.LogicalType;
import org.apache.avro.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/** Avro schemas → OpenMetadata topic schema fields; Iceberg schemas → OpenMetadata table columns. */
final class SchemaMapping {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SchemaMapping() {
    }

    /** Fields of an Avro record as {@code messageSchema.schemaFields}: one RECORD root with its fields as children. */
    static ArrayNode avroFields(Schema record) {
        ArrayNode root = JSON.createArrayNode();
        ObjectNode r = root.addObject();
        r.put("name", record.getName());
        r.put("dataType", "RECORD");
        r.put("dataTypeDisplay", record.getFullName());
        if (record.getDoc() != null) {
            r.put("description", record.getDoc());
        }
        ArrayNode children = r.putArray("children");
        for (Schema.Field f : record.getFields()) {
            children.add(avroField(f.name(), f.schema(), f.doc()));
        }
        return root;
    }

    private static ObjectNode avroField(String name, Schema schema, String doc) {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", name);
        Schema s = schema;
        if (s.getType() == Schema.Type.UNION && s.getTypes().size() == 2 && s.getTypes().get(0).getType() == Schema.Type.NULL) {
            s = s.getTypes().get(1);    // optional field
        }
        LogicalType lt = s.getLogicalType();
        String type = switch (s.getType()) {
            case RECORD -> "RECORD";
            case ENUM -> "ENUM";
            case ARRAY -> "ARRAY";
            case MAP -> "MAP";
            case UNION -> "UNION";
            case FIXED -> "FIXED";
            case STRING -> "STRING";
            case BYTES -> "BYTES";
            case INT -> "INT";
            case LONG -> lt != null && lt.getName().startsWith("timestamp") ? "TIMESTAMP" : "LONG";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case NULL -> "NULL";
        };
        n.put("dataType", type);
        n.put("dataTypeDisplay", schema.getType() == Schema.Type.UNION ? unionDisplay(schema)
                : lt != null ? lt.getName() : s.getType() == Schema.Type.RECORD || s.getType() == Schema.Type.ENUM
                ? s.getFullName() : s.getType().getName() + (s.getType() == Schema.Type.ARRAY
                ? "<" + s.getElementType().getType().getName() + ">" : s.getType() == Schema.Type.MAP
                ? "<string," + s.getValueType().getType().getName() + ">" : ""));
        if (doc != null) {
            n.put("description", doc);
        }
        if (s.getType() == Schema.Type.RECORD) {
            ArrayNode children = n.putArray("children");
            for (Schema.Field f : s.getFields()) {
                children.add(avroField(f.name(), f.schema(), f.doc()));
            }
        }
        return n;
    }

    private static String unionDisplay(Schema union) {
        StringBuilder sb = new StringBuilder("union<");
        for (int i = 0; i < union.getTypes().size(); i++) {
            Schema t = union.getTypes().get(i);
            sb.append(i == 0 ? "" : ",").append(t.getLogicalType() != null ? t.getLogicalType().getName()
                    : t.getType() == Schema.Type.RECORD ? t.getName() : t.getType().getName());
        }
        return sb.append(">").toString();
    }

    /** Columns of an Iceberg schema. */
    static ArrayNode icebergColumns(org.apache.iceberg.Schema schema) {
        ArrayNode cols = JSON.createArrayNode();
        int pos = 1;
        for (Types.NestedField f : schema.columns()) {
            ObjectNode c = cols.addObject();
            c.put("name", f.name());
            Type t = f.type();
            c.put("dataType", omType(t));
            c.put("dataTypeDisplay", t.toString());
            if (t.isListType()) {
                c.put("arrayDataType", omType(t.asListType().elementType()));
            }
            if (t.typeId() == Type.TypeID.FIXED) {
                // the server requires a length for BINARY (DatabaseUtil.validateColumnDataLength in 2.0.5)
                c.put("dataLength", ((Types.FixedType) t).length());
            }
            if (f.doc() != null) {
                c.put("description", f.doc());
            }
            c.put("constraint", f.isRequired() ? "NOT_NULL" : "NULL");
            c.put("ordinalPosition", pos++);
        }
        return cols;
    }

    private static String omType(Type t) {
        return switch (t.typeId()) {
            case STRING -> "STRING";
            case LONG -> "BIGINT";
            case INTEGER -> "INT";
            case DOUBLE -> "DOUBLE";
            case FLOAT -> "FLOAT";
            case BOOLEAN -> "BOOLEAN";
            case BINARY -> "BYTES";     // variable length; BINARY and VARBINARY need a dataLength
            case FIXED -> "BINARY";
            case TIMESTAMP -> ((Types.TimestampType) t).shouldAdjustToUTC() ? "TIMESTAMPZ" : "TIMESTAMP";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case DECIMAL -> "DECIMAL";
            case UUID -> "UUID";
            case LIST -> "ARRAY";
            case MAP -> "MAP";
            case STRUCT -> "STRUCT";
            default -> "UNKNOWN";
        };
    }
}
