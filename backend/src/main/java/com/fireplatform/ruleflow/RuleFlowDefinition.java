package com.fireplatform.ruleflow;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Digests;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Structural graph parsing and canonical encoding. Structural damage is a request error; per-node semantic
 * problems stay compile issues so an incomplete draft can still be saved and previewed.
 */
public final class RuleFlowDefinition {
    private RuleFlowDefinition() { }

    public static final int MAX_NODES = 24;
    public static final int MAX_EDGES = 48;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_NAME = 120;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_REASON = 1000;
    private static final Set<String> NODE_KEYS = Set.of("id", "type", "config", "x", "y");
    private static final Set<String> EDGE_KEYS = Set.of("from", "to");

    /** Parses and structurally validates the stored transport shape. Unknown keys are rejected, not ignored. */
    public static RuleFlowModels.Definition parse(Map<String, Object> raw) {
        if (raw == null) throw ApiError.bad("规则流定义不能为空");
        for (String key : raw.keySet()) {
            if (!Set.of("nodes", "edges").contains(key)) throw ApiError.bad("规则流定义包含不支持的结构字段");
        }
        List<?> rawNodes = list(raw.get("nodes"), "规则流必须包含节点列表");
        List<?> rawEdges = raw.get("edges") == null ? List.of() : list(raw.get("edges"), "规则流连线结构无效");
        if (rawNodes.isEmpty() || rawNodes.size() > MAX_NODES) throw ApiError.bad("规则流节点数量必须在1至" + MAX_NODES + "之间");
        if (rawEdges.size() > MAX_EDGES) throw ApiError.bad("规则流连线数量超过上限");
        List<RuleFlowModels.Node> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : rawNodes) {
            Map<String, Object> node = map(item, "节点结构无效");
            for (String key : node.keySet()) if (!NODE_KEYS.contains(key)) throw ApiError.bad("节点包含不支持的字段：" + key);
            String id = text(node.get("id"), 32, "节点ID无效");
            if (!id.matches("[A-Za-z0-9_-]{1,32}")) throw ApiError.bad("节点ID必须为1至32位字母、数字、下划线或连字符");
            if (!ids.add(id)) throw ApiError.bad("节点ID重复：" + id);
            String type = text(node.get("type"), 32, "节点类型无效");
            Map<String, Object> config = node.get("config") == null ? Map.of() : map(node.get("config"), "节点配置必须是对象");
            nodes.add(new RuleFlowModels.Node(id, type, Map.copyOf(config), coordinate(node.get("x")), coordinate(node.get("y"))));
        }
        List<RuleFlowModels.Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : rawEdges) {
            Map<String, Object> edge = map(item, "连线结构无效");
            for (String key : edge.keySet()) if (!EDGE_KEYS.contains(key)) throw ApiError.bad("连线包含不支持的字段：" + key);
            String from = text(edge.get("from"), 32, "连线起点无效");
            String to = text(edge.get("to"), 32, "连线终点无效");
            if (!ids.contains(from) || !ids.contains(to)) throw ApiError.bad("连线引用了不存在的节点");
            if (from.equals(to)) throw ApiError.bad("节点不能连接自身");
            if (!seen.add(from + "->" + to)) throw ApiError.bad("连线重复");
            edges.add(new RuleFlowModels.Edge(from, to));
        }
        return new RuleFlowModels.Definition(nodes, edges);
    }

    /** Encodes a definition back to the transport shape so the stored revision is what the editor showed. */
    public static Map<String, Object> encode(RuleFlowModels.Definition definition) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RuleFlowModels.Node node : definition.nodes()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("id", node.id());
            encoded.put("type", node.type());
            encoded.put("config", canonicalCopy(node.config()));
            encoded.put("x", node.x());
            encoded.put("y", node.y());
            nodes.add(encoded);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (RuleFlowModels.Edge edge : definition.edges()) edges.add(Map.of("from", edge.from(), "to", edge.to()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    public static String definitionSha256(RuleFlowModels.Definition definition) {
        return Digests.sha256(canonical(encode(definition)));
    }

    /** Stable key-sorted JSON so a hash never depends on map iteration order. */
    public static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** Sorted, JSON-safe deep copy used for storage and hashing. */
    public static Object canonicalCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), canonicalCopy(entry.getValue()));
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(canonicalCopy(item));
            return copy;
        }
        return value;
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            out.append('{');
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, entry.getKey());
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            out.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) out.append(',');
                write(out, list.get(index));
            }
            out.append(']');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        quote(out, String.valueOf(value));
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
    }

    private static List<?> list(Object value, String message) {
        if (!(value instanceof List<?> items)) throw ApiError.bad(message);
        return items;
    }

    private static Map<String, Object> map(Object value, String message) {
        if (!(value instanceof Map<?, ?> raw)) throw ApiError.bad(message);
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw ApiError.bad(message);
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String text(Object value, int max, String message) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw ApiError.bad(message);
        return text;
    }

    private static int coordinate(Object value) {
        if (value == null) return 0;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) throw ApiError.bad("节点坐标必须是整数");
        int parsed = number.intValue();
        if (parsed < 0 || parsed > 6000) throw ApiError.bad("节点坐标超出画布范围");
        return parsed;
    }
}
