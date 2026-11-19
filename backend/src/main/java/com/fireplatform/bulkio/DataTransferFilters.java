package com.fireplatform.bulkio;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.ScopeFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DataTransferFilters {
    private DataTransferFilters() { }
    public static ScopeFilter.Selection selection(Map<String,String> filters, Set<String> additionalKeys) {
        if (filters == null || filters.isEmpty()) return ScopeFilter.Selection.all();
        for (String key : filters.keySet()) {
            if (!Set.of("unit_ids", "place_ids").contains(key) && !additionalKeys.contains(key))
                throw ApiError.bad("不支持的导出筛选字段：" + key);
        }
        boolean explicit = filters.containsKey("unit_ids") || filters.containsKey("place_ids");
        var units = ids(filters.get("unit_ids"), 500, 64);
        var places = ids(filters.get("place_ids"), 1000, 64);
        return explicit && units.isEmpty() && places.isEmpty()
            ? ScopeFilter.Selection.none() : new ScopeFilter.Selection(units, places);
    }
    public static List<String> selectedIds(List<String> requested) {
        if (requested == null) return List.of();
        if (requested.size() > 10_000) throw ApiError.bad("勾选数量超过上限");
        var result = new LinkedHashSet<String>();
        for (String id : requested) {
            validId(id, 256);
            if (!result.add(id)) throw ApiError.bad("勾选ID重复");
        }
        return List.copyOf(result);
    }
    public static DataTransferDomain.ExportData selected(DataTransferDomain.ExportData data, List<String> requested) {
        if (requested == null) return data;
        var ids = selectedIds(requested);
        if (ids.isEmpty()) return new DataTransferDomain.ExportData(data.columns(), List.of());
        int column = data.columns().indexOf("id");
        if (column < 0) throw new IllegalStateException("勾选导出必须提供稳定id列");
        var wanted = new HashSet<>(ids);
        var remaining = new HashSet<>(ids);
        var rows = new ArrayList<List<String>>();
        for (var row : data.rows()) {
            if (wanted.contains(row.get(column))) {
                remaining.remove(row.get(column));
                rows.add(row);
            }
        }
        if (!remaining.isEmpty()) throw ApiError.forbidden("勾选记录不存在或不在当前授权及筛选范围内");
        return new DataTransferDomain.ExportData(data.columns(), rows);
    }
    private static List<String> ids(String value, int maximum, int maximumLength) {
        if (value == null || value.isEmpty()) return List.of();
        String[] parts = value.split(",", -1);
        if (parts.length > maximum) throw ApiError.bad("导出范围数量超过上限");
        var ids = new LinkedHashSet<String>();
        for (String id : parts) {
            validId(id, maximumLength);
            if (!ids.add(id)) throw ApiError.bad("导出范围ID重复");
        }
        return List.copyOf(ids);
    }
    private static void validId(String id, int maximumLength) {
        if (id == null || id.isBlank() || id.length() > maximumLength || !id.equals(id.trim())
            || id.indexOf(',') >= 0 || id.chars().anyMatch(character -> character < 32 || character == 127))
            throw ApiError.bad("导出范围ID无效");
    }
}
