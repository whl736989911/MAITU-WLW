package com.fireplatform.deviceservice.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.shared.ApiError;
import com.fireplatform.deviceservice.IntegrationDriver.DriverOutcome;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Allowlisted projection of legacy detail/service responses. Credentials never enter command history. */
final class LegacyWire {
    private static final Pattern SENSITIVE = Pattern.compile(
        "(?i).*(password|secret|token|credential|verification.?code|appkey|appskey|app.?key|app.?skey|"
            + "nwkskey|nwk.?skey|devaddr|dev.?addr|appa?eui|app.?eui).*");
    private static final Set<String> DETAIL_FIELDS = Set.of(
        "dId", "dDeviceId", "dEui", "dName", "dPid", "dProductId", "dAid", "dCompanyId", "dPlatform",
        "dNetworkModel", "dDescribe", "dDetail", "dDetial", "dParentId", "dNode", "dProductCategory",
        "dLastMsgTime", "dStatus", "dAddress", "dGps", "dChildSystem", "dHeartbeat", "dParam",
        "deviceParameters", "dModel", "battery", "rsrp", "sinr", "serviceCommond", "serviceName",
        "serviceParam", "commandType", "loraComandType", "dInstallMsg", "dShow", "isPush", "dType");
    private static final Set<String> SERVICE_FIELDS = Set.of(
        "paId", "paPid", "paName", "paDesc", "paType", "paPropertyType", "paDataType", "paDataRange",
        "paUnit", "paEventType", "paEventTypeDesc", "paParam", "paFlag");
    private static final Set<String> EMBEDDED_FIELDS = Set.of("dParam", "deviceParameters", "serviceParam");

    private final ObjectMapper json;

    LegacyWire(ObjectMapper json) { this.json = json; }

    /**
     * Exact remote identity check for an explicit ledger id. The legacy detail response must name the
     * requested dId and the device EUI must equal the bound serial. A missing field is unknown, never
     * an implied match; a different id or EUI is a definite mismatch and must not bind or mutate.
     */
    record RemoteIdentity(String state, String ledgerId, String eui, String deviceId, String note,
                          ObjectNode evidence) { }

    RemoteIdentity verify(JsonNode data, String requestedId, String serial, String source) {
        if (data == null || !data.isObject())
            return new RemoteIdentity("UNKNOWN", null, null, null, "旧平台设备详情结构未确认", null);
        ObjectNode evidence = detail(data, source);
        String id = text(data, "dId");
        String eui = text(data, "dEui");
        String deviceId = text(data, "dDeviceId");
        evidence.put("requested_ledger_id", requestedId);
        evidence.put("remote_lookup", true);
        if (requestedId == null || requestedId.isBlank())
            return new RemoteIdentity("UNKNOWN", id, eui, deviceId, "未提供精确台账ID(dId)，不能核对远端身份", evidence);
        if (id == null || id.isBlank())
            return new RemoteIdentity("UNKNOWN", null, eui, deviceId, "旧平台详情缺少dId，不能核对远端身份", evidence);
        evidence.put("ledger_id", id.trim());
        if (!id.trim().equals(requestedId.trim()))
            return new RemoteIdentity("NOT_FOUND", id, eui, deviceId, "旧平台详情dId与请求台账ID不一致，不能建立关联", evidence);
        if (eui == null || eui.isBlank())
            return new RemoteIdentity("UNKNOWN", id, null, deviceId, "旧平台详情缺少dEui，不能核对设备编号", evidence);
        evidence.put("remote_eui", eui.trim());
        if (serial == null || serial.isBlank())
            return new RemoteIdentity("UNKNOWN", id, eui, deviceId, "缺少已绑定设备编号，不能核对远端EUI", evidence);
        if (!eui.trim().equalsIgnoreCase(serial.trim()))
            return new RemoteIdentity("NOT_FOUND", id, eui, deviceId, "旧平台台账ID存在但EUI与登记编号不一致，不能建立关联", evidence);
        if (deviceId != null && !deviceId.isBlank()) evidence.put("remote_device_id", deviceId.trim());
        evidence.put("identity_verified", true);
        return new RemoteIdentity("FOUND", id.trim(), eui.trim(), deviceId, "旧平台详情EUI与台账ID已精确核对", evidence);
    }

    DriverOutcome deletionStatus(LegacyApiClient.ApiResult result, String requestedId, String serial, String source) {
        JsonNode code = result.root() == null ? null : result.root().get("code");
        if (result.status() == 404 || (result.status() >= 200 && result.status() < 300
            && code != null && code.isIntegralNumber() && code.canConvertToInt() && code.intValue() == 404)) {
            ObjectNode evidence = json.createObjectNode().put("source", source)
                .put("requested_ledger_id", requestedId).put("ledger_absence_confirmed", true);
            return DriverOutcome.succeeded("LEDGER_ABSENT", evidence,
                "已核对本账号下该精确台账ID不存在；不证明NS注销、终端停机或本地归档");
        }
        if (!result.accepted())
            return DriverOutcome.unknown("DELETE_QUERY_UNCONFIRMED", null, "台账查询未接受，不能据此确认删除；未重发删除");
        RemoteIdentity identity = verify(result.data(), requestedId, serial, source);
        if (!"FOUND".equals(identity.state()))
            return DriverOutcome.unknown("SOURCE_IDENTITY_UNCONFIRMED", null, "台账身份未确认，不能据此确认删除；未重发删除");
        return DriverOutcome.unknown("LEDGER_STILL_PRESENT", identity.evidence(), "该精确台账记录仍存在，删除效果未证实；未重发删除");
    }

    ObjectNode detail(JsonNode data, String source) {
        if (data == null || !data.isObject())
            throw ApiError.conflict("LEGACY_DETAIL_UNCONFIRMED", "旧平台设备详情结构未确认");
        ObjectNode out = json.createObjectNode();
        for (String field : DETAIL_FIELDS) {
            if (!data.has(field)) continue;
            JsonNode value = EMBEDDED_FIELDS.contains(field) ? sanitizeEmbedded(data.get(field)) : sanitize(data.get(field));
            out.set(field, value);
        }
        out.put("cached", true);
        out.put("source", source);
        out.put("note", "设备详情是旧平台缓存字段；dAttr/网络密钥已移除，不代表实时设备状态或终端执行结果");
        return out;
    }

    ObjectNode parameters(JsonNode data) {
        if (data == null || !data.isObject())
            throw ApiError.conflict("LEGACY_DETAIL_UNCONFIRMED", "旧平台设备详情结构未确认");
        List<Map.Entry<String, JsonNode>> values = new ArrayList<>();
        JsonNode parameters = parseEmbedded(data.get("deviceParameters"));
        JsonNode latest = parseEmbedded(data.get("dParam"));
        if (parameters != null && parameters.isArray()) {
            for (JsonNode item : parameters) if (item.isObject()) values.add(Map.entry("deviceParameters", item));
        } else if (parameters != null && parameters.isObject()) {
            parameters.fields().forEachRemaining(values::add);
        }
        if (latest != null && latest.isObject()) {
            latest.fields().forEachRemaining(values::add);
        } else if (latest != null && latest.isArray()) {
            for (JsonNode item : latest) if (item.isObject()) values.add(Map.entry("dParam", item));
        }
        ArrayNode projected = json.createArrayNode();
        ObjectNode schemaFields = json.createObjectNode();
        ArrayNode fields = schemaFields.putArray("fields");
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (Map.Entry<String, JsonNode> entry : values) {
            JsonNode value = entry.getValue();
            String type = entry.getKey();
            String name = type;
            JsonNode rawValue = value;
            if (value.isObject()) {
                type = text(value, "paramType");
                name = text(value, "paramName");
                rawValue = value.get("value");
                if (type == null) type = entry.getKey();
                if (name == null) name = type;
            }
            String identity = type + "\u0000" + name;
            if (!seen.add(identity)) continue;
            ObjectNode item = projected.addObject();
            item.put("paramType", type);
            item.put("paramName", name);
            boolean sensitive = SENSITIVE.matcher(type).matches() || SENSITIVE.matcher(name).matches();
            if (sensitive) item.put("value", "******");
            else if (rawValue == null || rawValue.isNull()) item.putNull("value");
            else if (rawValue.isValueNode()) item.set("value", sanitizeEmbedded(rawValue));
            else item.put("value", sanitizeEmbedded(rawValue).toString());
            String key = "parameter_" + index++;
            ObjectNode field = fields.addObject();
            field.put("key", key);
            field.put("label", name);
            field.put("type", "string");
            field.put("required", false);
            field.put("sensitive", sensitive);
            field.put("maxLength", 1024);
        }
        ObjectNode out = json.createObjectNode();
        out.set("parameters", projected);
        out.set("schema", schemaFields);
        out.put("cached", true);
        out.put("source", "旧平台设备详情缓存字段");
        out.put("note", "参数来自设备详情缓存，不是实时设备查询；打开页面不会唤醒设备");
        return out;
    }

    ObjectNode services(JsonNode data, String source) {
        if (data == null || data.isNull()) {
            ObjectNode empty = json.createObjectNode();
            empty.putArray("definitions");
            empty.put("count", 0);
            empty.put("source", source);
            empty.put("note", "旧平台返回空定义列表；这不证明该产品不支持任何服务");
            return empty;
        }
        if (!data.isArray()) throw ApiError.conflict("LEGACY_SERVICE_LIST_UNCONFIRMED", "旧平台服务定义结构未确认");
        ArrayNode definitions = json.createArrayNode();
        for (JsonNode item : data) {
            if (!item.isObject()) continue;
            ObjectNode projected = definitions.addObject();
            for (String field : SERVICE_FIELDS) if (item.has(field)) projected.set(field, sanitize(item.get(field)));
            projected.put("classification", "SOURCE_DECLARED");
        }
        ObjectNode out = json.createObjectNode();
        out.set("definitions", definitions);
        out.put("count", definitions.size());
        out.put("source", source);
        out.put("note", "定义由旧平台声明；属性/事件/服务语义保留，不代表每条记录都可下发或终端已执行");
        return out;
    }

    ObjectNode sanitizedData(JsonNode data) {
        JsonNode sanitized = sanitizeEmbedded(data);
        if (sanitized == null) return json.createObjectNode();
        if (sanitized.isObject()) return (ObjectNode) sanitized;
        ObjectNode out = json.createObjectNode();
        out.set("data", sanitized);
        return out;
    }

    private JsonNode sanitize(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            for (JsonNode item : node) copy.add(sanitize(item));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (SENSITIVE.matcher(entry.getKey()).matches()) continue;
                copy.set(entry.getKey(), sanitize(entry.getValue()));
            }
            return copy;
        }
        return node.deepCopy();
    }

    /**
     * Same key filtering plus parsing of JSON-encoded blobs while preserving their original wire
     * shape (a JSON string stays a JSON string). Used only for fields known to carry cached JSON so
     * a nested password/key cannot ride through as opaque text.
     */
    private JsonNode sanitizeEmbedded(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            for (JsonNode item : node) copy.add(sanitizeEmbedded(item));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (SENSITIVE.matcher(entry.getKey()).matches()) continue;
                copy.set(entry.getKey(), sanitizeEmbedded(entry.getValue()));
            }
            return copy;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (!value.isBlank() && (value.charAt(0) == '{' || value.charAt(0) == '[')) {
                try {
                    JsonNode embedded = json.readTree(value);
                    if (embedded != null && (embedded.isObject() || embedded.isArray()))
                        return json.getNodeFactory().textNode(sanitizeEmbedded(embedded).toString());
                } catch (Exception notJson) {
                    // Keep ordinary text unchanged; only structurally valid embedded JSON is parsed.
                }
            }
        }
        return node.deepCopy();
    }

    private JsonNode parseEmbedded(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            String text = node.asText();
            if (text.isBlank()) return null;
            try { return json.readTree(text); }
            catch (Exception invalid) { return null; }
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
