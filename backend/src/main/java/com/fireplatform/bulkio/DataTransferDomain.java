package com.fireplatform.bulkio;

import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Domain-owned commands and projections; the job engine owns files, authorization and row transactions. */
public interface DataTransferDomain {
    record Kind(String kind, List<String> columns, String readPermission, String writePermission,
                boolean importable, boolean exportable, boolean tenantRequired,
                Set<String> audiences, String effect) {
        public Kind {
            columns = List.copyOf(columns);
            audiences = Set.copyOf(audiences);
        }
    }
    record ExportData(List<String> columns, List<List<String>> rows) {
        public ExportData {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            if (rows.size() > 10_000) throw ApiError.bad("导出超过10000行，请收窄范围");
            for (var row : rows) if (row.size() != columns.size()) throw new IllegalStateException("导出列数不一致");
        }
    }
    List<Kind> kinds();
    default List<String> validate(String kind, Map<String,String> cells, AuthService.Identity actor, String tenant) {
        throw ApiError.bad("该类型不支持导入");
    }
    default String operation(String kind, Map<String,String> cells) {
        throw ApiError.bad("该类型不支持导入");
    }
    default String apply(String kind, Map<String,String> cells, AuthService.Identity actor, String tenant,
                         String reason, String jobId, int rowNumber) {
        throw ApiError.bad("该类型不支持导入");
    }
    default ExportData export(String kind, AuthService.Identity actor, String tenant,
                              Map<String,String> filters, List<String> selectedIds) {
        throw ApiError.bad("该类型不支持导出");
    }
}
