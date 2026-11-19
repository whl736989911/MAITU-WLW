package com.fireplatform.configuration;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Limits;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.WriteAuthorization;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService implements com.fireplatform.shared.PageSizePolicy {
    @Override public long defaultPageSize() { return value("default_page_size"); }
    public record SettingView(String key, long value, String unit, long min, long max, String description, long revision) { }
    public record UpdateSetting(@NotNull Long value, @NotNull @Min(0) Long expectedRevision) { }
    private final JdbcTemplate jdbc;
    private final WriteAuthorization auth;
    private final AuditService audit;
    public SettingsService(JdbcTemplate jdbc, WriteAuthorization auth, AuditService audit) { this.jdbc = jdbc; this.auth = auth; this.audit = audit; }
    public long value(String key) { return get(key).value(); }
    public List<SettingView> list() { return Limits.SETTINGS.keySet().stream().sorted().map(this::get).toList(); }
    public SettingView get(String key) {
        var definition = Limits.SETTINGS.get(key);
        if (definition == null) throw ApiError.missing();
        return jdbc.queryForObject("SELECT setting_value,revision FROM numeric_settings WHERE setting_key=?", (rs,n) ->
            new SettingView(key, rs.getLong(1), definition.unit(), definition.min(), definition.max(), definition.description(), rs.getLong(2)), key);
    }
    @Transactional
    public SettingView update(AuthService.Identity identity, String key, UpdateSetting input, String requestId) {
        auth.lockAndAuthorize(identity, "settings:write", null, "management");
        var def = Limits.SETTINGS.get(key);
        if (def == null) throw ApiError.missing();
        if (input.value() < def.min() || input.value() > def.max()) throw ApiError.bad("设置值超出允许范围");
        int changed = jdbc.update("UPDATE numeric_settings SET setting_value=?,revision=revision+1 WHERE setting_key=? AND revision=?", input.value(), key, input.expectedRevision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "设置已被更新，请刷新后重试");
        audit.record(identity.user().id(), null, "UPDATE_SETTING", key, "SUCCESS", requestId);
        return get(key);
    }
}
