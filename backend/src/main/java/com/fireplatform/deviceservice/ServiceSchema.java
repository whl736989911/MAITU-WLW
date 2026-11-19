package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.shared.ApiError;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Declarative operation input schema (field list, not executable code) shared by validation,
 * persistence and UI rendering. Unknown keys are rejected so a vendor argument can never be
 * smuggled past an operator-visible field.
 */
@Component
public final class ServiceSchema {
    public static final int MAX_FIELDS = 32;
    public static final int MAX_ARGUMENT_BYTES = 16384;
    public static final int MAX_SCHEMA_BYTES = 16384;
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");
    private static final Set<String> FIELD_KEYS = Set.of("key", "label", "type", "required", "sensitive", "minimum", "maximum", "maxLength", "enum");

    private final ObjectMapper json;

    public ServiceSchema(ObjectMapper json) { this.json = json; }

    public record Field(String key, String label, String type, boolean required, boolean sensitive,
                        BigDecimal minimum, BigDecimal maximum, Integer maxLength, List<String> allowed) { }

    public List<Field> parse(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw ApiError.bad("服务输入schema必须是JSON对象");
        var iterator = schema.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!"fields".equals(entry.getKey())) throw ApiError.bad("服务输入schema仅支持fields字段");
        }
        JsonNode fields = schema.path("fields");
        if (!fields.isArray() || fields.size() > MAX_FIELDS) throw ApiError.bad("服务输入字段数量无效");
        List<Field> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode node : fields) {
            if (!node.isObject()) throw ApiError.bad("服务输入字段必须是JSON对象");
            var names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!FIELD_KEYS.contains(name)) throw ApiError.bad("服务输入字段含未知属性：" + name);
            }
            String key = node.path("key").asText("");
            if (!KEY.matcher(key).matches()) throw ApiError.bad("服务输入字段key无效");
            if (!keys.add(key)) throw ApiError.bad("服务输入字段key重复");
            String label = node.path("label").asText("");
            if (label.isBlank() || label.length() > 200) throw ApiError.bad("服务输入字段名称无效");
            String type = node.path("type").asText("");
            if (!TYPES.contains(type)) throw ApiError.bad("服务输入字段类型仅支持string/integer/number/boolean");
            boolean required = booleanField(node, "required");
            boolean sensitive = booleanField(node, "sensitive");
            Integer maxLength = null;
            if (node.has("maxLength")) {
                if (!"string".equals(type)) throw ApiError.bad("仅string字段可配置maxLength");
                JsonNode value = node.get("maxLength");
                if (!value.isIntegralNumber() || value.asInt() < 1 || value.asInt() > 1024) throw ApiError.bad("maxLength必须在1-1024之间");
                maxLength = value.asInt();
            }
            BigDecimal minimum = null, maximum = null;
            if (node.has("minimum") || node.has("maximum")) {
                if (!"integer".equals(type) && !"number".equals(type)) throw ApiError.bad("仅数值字段可配置minimum/maximum");
                if (node.has("minimum")) minimum = number(node.get("minimum"), "minimum");
                if (node.has("maximum")) maximum = number(node.get("maximum"), "maximum");
                if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw ApiError.bad("minimum不能大于maximum");
            }
            List<String> allowed = List.of();
            if (node.has("enum")) {
                if (!"string".equals(type)) throw ApiError.bad("仅string字段可配置enum");
                JsonNode values = node.get("enum");
                if (!values.isArray() || values.isEmpty() || values.size() > MAX_FIELDS) throw ApiError.bad("enum取值数量无效");
                var items = new ArrayList<String>();
                for (JsonNode value : values) {
                    if (!value.isTextual() || value.asText().length() > 256) throw ApiError.bad("enum取值必须是有限字符串");
                    if (items.contains(value.asText())) throw ApiError.bad("enum取值重复");
                    items.add(value.asText());
                }
                allowed = List.copyOf(items);
            }
            result.add(new Field(key, label, type, required, sensitive, minimum, maximum, maxLength, allowed));
        }
        String encoded = schema.toString();
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) throw ApiError.bad("服务输入schema超过限制");
        return List.copyOf(result);
    }

    public JsonNode canonicalSchema(JsonNode schema) {
        parse(schema);
        return schema.deepCopy();
    }

    /** Returns a canonical, schema-ordered argument object or rejects the request. */
    public ObjectNode validateArguments(JsonNode arguments, List<Field> fields) {
        if (arguments == null || !arguments.isObject()) throw ApiError.bad("命令参数必须是JSON对象");
        Map<String, Field> byKey = new LinkedHashMap<>();
        for (Field field : fields) byKey.put(field.key(), field);
        var names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!byKey.containsKey(name)) throw ApiError.bad("命令参数含未声明字段：" + name);
        }
        ObjectNode canonical = json.createObjectNode();
        for (Field field : fields) {
            JsonNode value = arguments.get(field.key());
            if (value == null || value.isNull()) {
                if (field.required()) throw ApiError.bad("命令参数缺少必填字段：" + field.label());
                continue;
            }
            switch (field.type()) {
                case "string" -> {
                    if (!value.isTextual()) throw ApiError.bad("命令参数字段类型无效：" + field.label());
                    String text = value.asText();
                    if (text.length() > (field.maxLength() == null ? 1024 : field.maxLength())) throw ApiError.bad("命令参数字段超过长度限制：" + field.label());
                    if (!field.allowed().isEmpty() && !field.allowed().contains(text)) throw ApiError.bad("命令参数字段取值不在允许范围：" + field.label());
                    canonical.put(field.key(), text);
                }
                case "integer" -> {
                    if (!value.isIntegralNumber()) throw ApiError.bad("命令参数字段必须是整数：" + field.label());
                    bounded(new BigDecimal(value.bigIntegerValue()), field);
                    canonical.put(field.key(), value.bigIntegerValue());
                }
                case "number" -> {
                    if (!value.isNumber()) throw ApiError.bad("命令参数字段必须是数值：" + field.label());
                    BigDecimal decimal = value.decimalValue();
                    bounded(decimal, field);
                    canonical.put(field.key(), decimal);
                }
                case "boolean" -> {
                    if (!value.isBoolean()) throw ApiError.bad("命令参数字段必须是布尔值：" + field.label());
                    canonical.put(field.key(), value.booleanValue());
                }
                default -> throw ApiError.bad("命令参数字段类型未支持");
            }
        }
        if (canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) throw ApiError.bad("命令参数超过限制");
        return canonical;
    }

    public ObjectNode mask(ObjectNode canonical, List<Field> fields) {
        ObjectNode masked = canonical.deepCopy();
        for (Field field : fields) {
            if (field.sensitive() && masked.has(field.key())) masked.put(field.key(), "******");
        }
        return masked;
    }

    public boolean sensitive(List<Field> fields, String key) {
        return fields.stream().anyMatch(field -> field.key().equals(key) && field.sensitive());
    }

    private static void bounded(BigDecimal value, Field field) {
        if (field.minimum() != null && value.compareTo(field.minimum()) < 0) throw ApiError.bad("命令参数字段低于最小值：" + field.label());
        if (field.maximum() != null && value.compareTo(field.maximum()) > 0) throw ApiError.bad("命令参数字段高于最大值：" + field.label());
    }

    private static boolean booleanField(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null) return false;
        if (!value.isBoolean()) throw ApiError.bad("服务输入字段" + name + "必须是布尔值");
        return value.booleanValue();
    }

    private static BigDecimal number(JsonNode value, String name) {
        if (!value.isNumber()) throw ApiError.bad(name + "必须是数值");
        return value.decimalValue();
    }
}
