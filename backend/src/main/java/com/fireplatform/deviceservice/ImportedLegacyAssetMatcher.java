package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.shared.ApiError;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Exact, read-only match of an already imported legacy ledger asset. It only considers devices that
 * carry a legacy import alias or the importer's legacy id shape; it never fuzzy-matches, never takes
 * the first row, and never calls the remote ledger. The operator must supply the ledger id (dId).
 */
@Service
public class ImportedLegacyAssetMatcher {
    public record Match(String state, String externalDeviceId, String deviceId, String serial, String note) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ImportedLegacyAssetMatcher(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Match match(String tenantId, String driverKey, String serial, String externalDeviceId) {
        if (tenantId == null || tenantId.isBlank()) throw ApiError.bad("关联已导入旧台账资产必须先确定本地单位");
        if (serial == null || serial.isBlank()) throw ApiError.bad("关联已导入旧台账资产必须提供精确设备编号");
        if (externalDeviceId == null || externalDeviceId.isBlank())
            throw ApiError.bad("关联已导入旧台账资产必须提供旧平台台账ID(dId)，不能用名称或首行推断");
        if (driverKey == null || driverKey.isBlank())
            throw ApiError.bad("关联已导入旧台账资产必须指定接入驱动");
        String prefix = switch (driverKey) {
            case "LEGACY_TSF" -> "LEGACY-TSF-";
            case "LEGACY_TTL" -> "LEGACY-TTL-";
            default -> throw ApiError.bad("该接入驱动没有已导入旧台账资产命名空间");
        };
        String expectedDeviceId = prefix + externalDeviceId.trim();
        List<String[]> rows = jdbc.query("""
            SELECT DISTINCT d.id,d.device_eui
              FROM devices d
             WHERE d.tenant_id=? AND d.archived_at IS NULL
               AND (d.id LIKE ? OR EXISTS (SELECT 1 FROM device_aliases a WHERE a.device_id=d.id AND a.tenant_id=d.tenant_id AND a.origin=?))
               AND (d.device_eui=? OR EXISTS (SELECT 1 FROM device_aliases e WHERE e.device_id=d.id AND e.tenant_id=d.tenant_id AND e.kind='EUI' AND e.alias_value=?))
               AND d.id=?
            """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2)},
            tenantId, prefix + "%", driverKey, serial.trim(), serial.trim(), expectedDeviceId);
        if (rows.isEmpty())
            return new Match("NOT_FOUND", externalDeviceId, null, serial,
                "本平台导入台账中没有匹配该精确编号与台账ID的资产；未调用远端查询，不能用猜测建立关联");
        if (rows.size() > 1)
            return new Match("AMBIGUOUS", externalDeviceId, null, serial,
                "精确编号与台账ID命中多台导入资产；未建立关联，需先解决台账冲突");
        String[] row = rows.getFirst();
        String deviceId = row[0], deviceEui = row[1];
        if (!deviceId.equals(expectedDeviceId))
            return new Match("NOT_FOUND", externalDeviceId, null, serial,
                "导入资产的台账命名空间与请求驱动不一致；不能跨来源关联");
        ObjectNode result = json.createObjectNode();
        result.put("device_id", deviceId);
        result.put("device_eui", deviceEui);
        result.put("ledger_id", externalDeviceId);
        result.put("driver_key", driverKey);
        result.put("matched_locally", true);
        result.put("remote_lookup", false);
        return new Match("FOUND", externalDeviceId, deviceId, deviceEui == null ? serial : deviceEui,
            "仅在本平台导入台账中按精确编号与台账ID匹配；未调用远端查询，未开通/注销任何NS资源");
    }
}
