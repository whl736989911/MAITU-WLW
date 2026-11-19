package com.fireplatform.application;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant/unit-scoped reads. Applications are unit-owned records: they follow the selected unit/place tree and the
 * caller's current unit grants, never device-level attribution. Endpoint URLs, credentials and payload bytes are
 * never projected; only the readable origin, a masked path and digests are returned.
 */
@Service
public class ApplicationQueries {
    private static final String SUMMARY_COLUMNS = "a.id,a.tenant_id,t.name AS tenant_name,a.name,a.description,"
        + "a.status,a.revision,"
        + "(SELECT COUNT(*) FROM application_product_authorizations z WHERE z.application_id=a.id AND z.tenant_id=a.tenant_id AND z.status='ACTIVE'),"
        + "(SELECT COUNT(*) FROM application_outputs o WHERE o.application_id=a.id AND o.tenant_id=a.tenant_id),"
        + "(SELECT COUNT(*) FROM application_outputs o WHERE o.application_id=a.id AND o.tenant_id=a.tenant_id AND o.enabled=TRUE),"
        + "(SELECT COUNT(*) FROM application_delivery_outbox d WHERE d.application_id=a.id AND d.tenant_id=a.tenant_id AND d.status='PENDING'),"
        + "(SELECT COUNT(*) FROM application_delivery_outbox d WHERE d.application_id=a.id AND d.tenant_id=a.tenant_id AND d.status='UNKNOWN'),"
        + "(SELECT COUNT(*) FROM application_delivery_outbox d WHERE d.application_id=a.id AND d.tenant_id=a.tenant_id AND d.status='BLOCKED'),"
        + "a.created_at,a.updated_at,a.archived_at,ab.display_name AS archived_by_name,a.archived_reason";
    private static final String SUMMARY_FROM = " FROM output_applications a JOIN tenants t ON t.id=a.tenant_id"
        + " LEFT JOIN app_users ab ON ab.id=a.archived_by";
    private static final String AUTHORIZATION_COLUMNS = "z.id,z.reference_kind,z.reference_key,z.product_id,"
        + "p.name AS product_name,p.manufacturer,p.model,z.product_version_id,v.version_number,z.source_platform,"
        + "z.legacy_product_id,z.legacy_decoder_revision,z.legacy_decoder_sha256,z.vendor_model_id,z.driver_key,"
        + "z.adapter_digest,z.scope_mode,z.status,z.revision,"
        + "(SELECT COUNT(*) FROM application_authorization_devices s WHERE s.authorization_id=z.id AND s.tenant_id=z.tenant_id) AS device_count,"
        + "cb.display_name AS created_by,z.created_at,z.updated_at,z.revoked_at,z.revoked_reason";
    private static final String AUTHORIZATION_FROM = " FROM application_product_authorizations z"
        + " LEFT JOIN products p ON p.id=z.product_id"
        + " LEFT JOIN product_versions v ON v.id=z.product_version_id"
        + " LEFT JOIN app_users cb ON cb.id=z.created_by";
    private static final String DELIVERY_COLUMNS = "d.id,d.application_id,d.output_id,o.name AS output_name,d.fact_id,"
        + "d.device_id,dev.name AS device_name,d.product_version_id,d.source_platform,d.source_product_kind,"
        + "d.source_product_id,d.source_driver_key,d.source_product_digest,d.authorization_reference_kind,"
        + "d.authorization_reference_key,d.source_message_id,d.source_event_type,d.source_event_at,d.received_at,"
        + "d.alarm_id,d.mode,"
        + "CASE WHEN d.frozen_endpoint_origin LIKE 'mqtts://%' THEN 'MQTT_TLS_3_1_1' ELSE 'HTTPS' END AS protocol,"
        + "d.single_device_proof,d.content_type,d.payload_sha256,d.payload_bytes,d.status,"
        + "d.failure_code,d.http_status,d.attempt_count,d.captured_at,d.claimed_at,d.finished_at";
    private static final String DELIVERY_FROM = " FROM application_delivery_outbox d"
        + " LEFT JOIN application_outputs o ON o.id=d.output_id AND o.tenant_id=d.tenant_id"
        + " LEFT JOIN devices dev ON dev.id=d.device_id AND dev.tenant_id=d.tenant_id";

    private final JdbcTemplate jdbc;
    private final ScopeFilter scope;
    private final PageSizePolicy sizes;
    private final TransactionRequirements transactions;
    private final List<com.fireplatform.deviceservice.IntegrationDriver> drivers;

    public ApplicationQueries(JdbcTemplate jdbc, ScopeFilter scope, PageSizePolicy sizes,
                              TransactionRequirements transactions,
                              List<com.fireplatform.deviceservice.IntegrationDriver> drivers) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.sizes = sizes;
        this.transactions = transactions;
        this.drivers = drivers == null ? List.of() : List.copyOf(drivers);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationModels.ApplicationPage page(String actor, String tenant, ScopeFilter.Selection selection,
                                                  String status, String keyword, int page, Integer requestedSize) {
        transactions.repeatableReadOnly();
        Pagination pagination = Pagination.of(page, requestedSize, sizes.defaultPageSize());
        if (selection.empty()) return new ApplicationModels.ApplicationPage(List.of(), 0, pagination.page(), pagination.size());
        String normalizedStatus = status(status);
        String normalizedKeyword = ApplicationInputs.keyword(keyword);
        var units = scope.units(actor, "a", "tenant_id", tenant, selection);
        var args = new ArrayList<Object>(units.bindings());
        var where = new StringBuilder(" WHERE ").append(units.sql());
        if (normalizedStatus != null) { where.append(" AND a.status=?"); args.add(normalizedStatus); }
        if (!normalizedKeyword.isEmpty()) {
            String like = LiteralLike.contains(normalizedKeyword);
            where.append(" AND (LOWER(a.name) LIKE LOWER(?) ESCAPE '!' OR LOWER(a.description) LIKE LOWER(?) ESCAPE '!')");
            args.add(like);
            args.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM output_applications a" + where, Long.class, args.toArray());
        var window = new ArrayList<>(args);
        window.add(pagination.size());
        window.add(pagination.offset());
        var items = jdbc.query("SELECT " + SUMMARY_COLUMNS + SUMMARY_FROM + where
            + " ORDER BY a.updated_at DESC,a.id DESC LIMIT ? OFFSET ?", ApplicationQueries::summary, window.toArray());
        return new ApplicationModels.ApplicationPage(items, total, pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationModels.ApplicationDetail detail(String actor, String tenant, String id) {
        transactions.repeatableReadOnly();
        return detailFor(summaryFor(actor, tenant, id));
    }

    private ApplicationModels.ApplicationDetail detailFor(ApplicationModels.ApplicationSummary summary) {
        var authorizations = jdbc.query("SELECT " + AUTHORIZATION_COLUMNS + AUTHORIZATION_FROM
                + " WHERE z.tenant_id=? AND z.application_id=? ORDER BY z.created_at,z.id",
            ApplicationQueries::authorization, summary.tenantId(), summary.id());
        var outputs = jdbc.query("SELECT o.id,o.name,o.mode,o.protocol,o.enabled,o.endpoint_origin,o.endpoint_masked,"
                + "o.endpoint_digest,o.auth_scheme,o.auth_header_name,o.credential_masked,o.credential_digest,"
                + "o.mqtt_auth_scheme,o.mqtt_topic_masked,o.mqtt_topic_digest,o.mqtt_username_masked,"
                + "o.mqtt_username_digest,o.mqtt_password_masked,o.mqtt_password_digest,"
                + "o.timeout_ms,o.revision,o.created_at,o.updated_at"
                + " FROM application_outputs o WHERE o.tenant_id=? AND o.application_id=? ORDER BY o.created_at,o.id",
            (rs, n) -> new ApplicationModels.Output(rs.getString("id"), rs.getString("name"), rs.getString("mode"),
                rs.getString("protocol"), rs.getBoolean("enabled"), rs.getString("endpoint_origin"),
                rs.getString("endpoint_masked"), rs.getString("endpoint_digest"), rs.getString("auth_scheme"),
                rs.getString("auth_header_name"), rs.getString("credential_masked"),
                rs.getString("credential_digest"), rs.getString("mqtt_auth_scheme"),
                rs.getString("mqtt_topic_masked"), rs.getString("mqtt_topic_digest"),
                rs.getString("mqtt_username_masked"), rs.getString("mqtt_username_digest"),
                rs.getString("mqtt_password_masked"), rs.getString("mqtt_password_digest"),
                rs.getInt("timeout_ms"), rs.getLong("revision"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)),
            summary.tenantId(), summary.id());
        return new ApplicationModels.ApplicationDetail(summary, authorizations, outputs,
            deliverySummary(summary.tenantId(), summary.id()));
    }

    /**
     * Authoritative identity options for the grant picker. Legacy ids come from the existing decoder registry;
     * vendor models come from discovery metadata bound to actual unit devices. No name inference, no first-model.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationModels.ReferenceOptions referenceOptions(String actor, String tenant, String kind,
                                                               String sourcePlatform, String keyword, int page,
                                                               Integer requestedSize) {
        transactions.repeatableReadOnly();
        String normalizedKind = ApplicationInputs.referenceKind(kind);
        if (tenant == null || tenant.isBlank()) throw ApiError.bad("必须明确选择授权所属单位");
        Long grant = jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants g JOIN tenants t ON t.id=g.tenant_id"
            + " AND t.enabled=TRUE WHERE g.user_id=? AND g.tenant_id=?", Long.class, actor, tenant);
        if (grant == null || grant == 0L) throw ApiError.forbidden("未授权访问此单位");
        Pagination pagination = Pagination.of(page, requestedSize, sizes.defaultPageSize());
        String normalizedKeyword = ApplicationInputs.keyword(keyword);
        String like = normalizedKeyword.isEmpty() ? null : LiteralLike.contains(normalizedKeyword);
        if ("LEGACY_PRODUCT".equals(normalizedKind)) {
            var platforms = jdbc.queryForList("SELECT DISTINCT vendor_platform FROM legacy_ingest_messages"
                + " WHERE tenant_id=? AND vendor_platform<>'hikvision_firecloud' ORDER BY vendor_platform", String.class,
                tenant);
            long total = jdbc.queryForObject("SELECT COUNT(DISTINCT legacy_product_id) FROM legacy_product_decoders"
                + (like == null ? "" : " WHERE LOWER(legacy_product_id) LIKE LOWER(?) ESCAPE '!'"), Long.class,
                like == null ? new Object[]{} : new Object[]{like});
            var items = jdbc.query("SELECT d.legacy_product_id,d.revision,d.source_sha256"
                    + " FROM legacy_product_decoders d JOIN (SELECT legacy_product_id,MAX(revision) AS revision"
                    + " FROM legacy_product_decoders GROUP BY legacy_product_id) m"
                    + " ON m.legacy_product_id=d.legacy_product_id AND m.revision=d.revision"
                    + (like == null ? "" : " WHERE LOWER(d.legacy_product_id) LIKE LOWER(?) ESCAPE '!'")
                    + " ORDER BY d.legacy_product_id LIMIT ? OFFSET ?",
                (rs, n) -> new ApplicationModels.ReferenceOption(rs.getString(1), rs.getString(1),
                    "解码器注册表 · 修订 " + rs.getLong(2), rs.getLong(2), rs.getString(3), null),
                like == null ? new Object[]{pagination.size(), pagination.offset()}
                    : new Object[]{like, pagination.size(), pagination.offset()});
            return new ApplicationModels.ReferenceOptions(normalizedKind, platforms, List.of(), items, total,
                pagination.page(), pagination.size());
        }
        if ("VENDOR_MODEL".equals(normalizedKind)) {
            long total = jdbc.queryForObject("SELECT COUNT(DISTINCT h.model) FROM hikvision_discovered_devices h"
                + " JOIN devices d ON d.id=h.local_device_id WHERE d.tenant_id=? AND h.model IS NOT NULL"
                + (like == null ? "" : " AND LOWER(h.model) LIKE LOWER(?) ESCAPE '!'"), Long.class,
                like == null ? new Object[]{tenant} : new Object[]{tenant, like});
            var items = jdbc.query("SELECT h.model,COUNT(DISTINCT h.local_device_id) AS device_count"
                    + " FROM hikvision_discovered_devices h JOIN devices d ON d.id=h.local_device_id"
                    + " WHERE d.tenant_id=? AND h.model IS NOT NULL"
                    + (like == null ? "" : " AND LOWER(h.model) LIKE LOWER(?) ESCAPE '!'")
                    + " GROUP BY h.model ORDER BY h.model LIMIT ? OFFSET ?",
                (rs, n) -> new ApplicationModels.ReferenceOption(rs.getString(1), rs.getString(1),
                    "发现元数据 · 已绑定 " + rs.getInt(2) + " 台设备", null, null, rs.getInt(2)),
                like == null ? new Object[]{tenant, pagination.size(), pagination.offset()}
                    : new Object[]{tenant, like, pagination.size(), pagination.offset()});
            var driverList = drivers.stream()
                .map(driver -> new ApplicationModels.Driver(driver.key(), driver.displayName())).toList();
            return new ApplicationModels.ReferenceOptions(normalizedKind, List.of("hikvision_firecloud"),
                driverList, items, total, pagination.page(), pagination.size());
        }
        return new ApplicationModels.ReferenceOptions(normalizedKind, List.of(), List.of(), List.of(), 0,
            pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationModels.DeliveryPage deliveries(String actor, String tenant, String applicationId,
                                                     String status, String outputId, int page, Integer requestedSize) {
        transactions.repeatableReadOnly();
        var summary = summaryFor(actor, tenant, applicationId);
        Pagination pagination = Pagination.of(page, requestedSize, sizes.defaultPageSize());
        String normalizedStatus = deliveryStatus(status);
        var args = new ArrayList<Object>();
        args.add(summary.tenantId());
        args.add(applicationId);
        var where = new StringBuilder(" WHERE d.tenant_id=? AND d.application_id=?");
        if (normalizedStatus != null) { where.append(" AND d.status=?"); args.add(normalizedStatus); }
        if (outputId != null && !outputId.isBlank()) { where.append(" AND d.output_id=?"); args.add(ApplicationInputs.outputId(outputId)); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM application_delivery_outbox d" + where, Long.class,
            args.toArray());
        var window = new ArrayList<>(args);
        window.add(pagination.size());
        window.add(pagination.offset());
        var items = jdbc.query("SELECT " + DELIVERY_COLUMNS + DELIVERY_FROM + where
            + " ORDER BY d.captured_at DESC,d.id DESC LIMIT ? OFFSET ?", ApplicationQueries::delivery, window.toArray());
        return new ApplicationModels.DeliveryPage(items, total, pagination.page(), pagination.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationModels.DeliveryDetail delivery(String actor, String tenant, String applicationId,
                                                     String deliveryId) {
        transactions.repeatableReadOnly();
        var summary = summaryFor(actor, tenant, applicationId);
        var rows = jdbc.query("SELECT " + DELIVERY_COLUMNS + DELIVERY_FROM
                + " WHERE d.tenant_id=? AND d.application_id=? AND d.id=?",
            ApplicationQueries::delivery, summary.tenantId(), applicationId, ApplicationInputs.deliveryId(deliveryId));
        if (rows.isEmpty()) throw ApiError.missing();
        var attempts = jdbc.query("SELECT t.id,t.attempt_no,t.output_revision,t.endpoint_origin,t.endpoint_masked,"
                + "t.endpoint_digest,t.mode,t.status,t.http_status,t.failure_code,t.started_at,t.finished_at,"
                + "t.duration_ms,"
                + "CASE WHEN d.frozen_endpoint_origin LIKE 'mqtts://%' THEN 'MQTT_TLS_3_1_1' ELSE 'HTTPS' END AS protocol"
                + " FROM application_delivery_attempts t"
                + " JOIN application_delivery_outbox d ON d.id=t.outbox_id AND d.tenant_id=t.tenant_id"
                + " WHERE t.tenant_id=? AND t.outbox_id=? ORDER BY t.attempt_no",
            (rs, n) -> new ApplicationModels.Attempt(rs.getString("id"), rs.getInt("attempt_no"),
                rs.getLong("output_revision"), rs.getString("endpoint_origin"), rs.getString("endpoint_masked"),
                rs.getString("endpoint_digest"), rs.getString("mode"), rs.getString("protocol"),
                rs.getString("status"), rs.getObject("http_status", Integer.class), rs.getString("failure_code"),
                ApplicationModels.meaning(rs.getString("status"), rs.getString("failure_code")),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                rs.getLong("duration_ms")),
            summary.tenantId(), deliveryId);
        return new ApplicationModels.DeliveryDetail(rows.getFirst(), attempts,
            "仅保留载荷摘要与长度，不读取也不保存远端响应正文或凭据回显",
            "HTTP 2xx 或 MQTT PUBACK 仅代表传输层接受，不等于远端业务处理；UNKNOWN不回退为失败，也不自动重发");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationModels.EventPage events(String actor, String tenant, String applicationId, int page,
                                              Integer requestedSize) {
        transactions.repeatableReadOnly();
        var summary = summaryFor(actor, tenant, applicationId);
        Pagination pagination = Pagination.of(page, requestedSize, sizes.defaultPageSize());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM output_application_events WHERE tenant_id=? AND application_id=?",
            Long.class, summary.tenantId(), applicationId);
        var items = jdbc.query("SELECT e.id,e.subject_type,e.subject_id,e.action,e.revision_before,e.revision_after,"
                + "e.actor_id,u.display_name AS actor_name,e.reason,e.created_at"
                + " FROM output_application_events e LEFT JOIN app_users u ON u.id=e.actor_id"
                + " WHERE e.tenant_id=? AND e.application_id=? ORDER BY e.created_at,e.id LIMIT ? OFFSET ?",
            (rs, n) -> new ApplicationModels.Event(rs.getString("id"), rs.getString("subject_type"),
                rs.getString("subject_id"), rs.getString("action"), rs.getObject("revision_before", Long.class),
                rs.getLong("revision_after"), rs.getString("actor_id"), rs.getString("actor_name"),
                rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class)),
            summary.tenantId(), applicationId, pagination.size(), pagination.offset());
        return new ApplicationModels.EventPage(items, total, pagination.page(), pagination.size());
    }

    /** Mode is immutable after creation; the update command receives it explicitly instead of guessing. */
    String outputMode(String tenant, String applicationId, String outputId) {
        var rows = jdbc.queryForList("SELECT mode FROM application_outputs WHERE tenant_id=? AND application_id=?"
            + " AND id=?", String.class, tenant, applicationId, outputId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** Protocol is immutable after creation; the same explicit hand-off as mode. */
    String outputProtocol(String tenant, String applicationId, String outputId) {
        var rows = jdbc.queryForList("SELECT protocol FROM application_outputs WHERE tenant_id=? AND application_id=?"
            + " AND id=?", String.class, tenant, applicationId, outputId);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    /** Post-write projection inside the caller's transaction; the command already enforced tenant authority. */
    ApplicationModels.ApplicationDetail current(String tenant, String id) {
        var rows = jdbc.query("SELECT " + SUMMARY_COLUMNS + SUMMARY_FROM + " WHERE a.tenant_id=? AND a.id=?",
            ApplicationQueries::summary, tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        return detailFor(rows.getFirst());
    }

    private ApplicationModels.ApplicationSummary summaryFor(String actor, String tenant, String id) {
        ApplicationInputs.applicationId(id);
        var args = new ArrayList<Object>();
        var sql = new StringBuilder("SELECT ").append(SUMMARY_COLUMNS).append(SUMMARY_FROM)
            .append(" WHERE a.id=? AND EXISTS(SELECT 1 FROM user_tenant_grants g JOIN tenants gt ON gt.id=g.tenant_id")
            .append(" AND gt.enabled=TRUE WHERE g.user_id=? AND g.tenant_id=a.tenant_id)");
        args.add(id);
        args.add(actor);
        if (tenant != null) { sql.append(" AND a.tenant_id=?"); args.add(tenant); }
        var rows = jdbc.query(sql.toString(), ApplicationQueries::summary, args.toArray());
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private ApplicationModels.DeliverySummary deliverySummary(String tenant, String applicationId) {
        return jdbc.queryForObject("SELECT "
                + "COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN status='IN_FLIGHT' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN status='UNKNOWN' THEN 1 ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN status='BLOCKED' THEN 1 ELSE 0 END),0),MAX(captured_at)"
                + " FROM application_delivery_outbox WHERE tenant_id=? AND application_id=?",
            (rs, n) -> new ApplicationModels.DeliverySummary(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class)),
            tenant, applicationId);
    }

    private static ApplicationModels.ApplicationSummary summary(ResultSet rs, int n) throws SQLException {
        return new ApplicationModels.ApplicationSummary(rs.getString("id"), rs.getString("tenant_id"),
            rs.getString("tenant_name"), rs.getString("name"), rs.getString("description"), rs.getString("status"),
            rs.getLong("revision"), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getLong(11), rs.getLong(12),
            rs.getLong(13), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("archived_at", OffsetDateTime.class),
            rs.getString("archived_by_name"), rs.getString("archived_reason"));
    }

    private static ApplicationModels.Authorization authorization(ResultSet rs, int n) throws SQLException {
        return new ApplicationModels.Authorization(rs.getString("id"), rs.getString("reference_kind"),
            rs.getString("reference_key"), rs.getString("product_id"), rs.getString("product_name"),
            rs.getString("manufacturer"), rs.getString("model"), rs.getString("product_version_id"),
            rs.getObject("version_number", Long.class), rs.getString("source_platform"),
            rs.getString("legacy_product_id"), rs.getObject("legacy_decoder_revision", Long.class),
            rs.getString("legacy_decoder_sha256"), rs.getString("vendor_model_id"), rs.getString("driver_key"),
            rs.getString("adapter_digest"), rs.getString("scope_mode"), rs.getString("status"),
            rs.getLong("revision"), rs.getInt("device_count"), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoked_reason"));
    }

    private static ApplicationModels.Delivery delivery(ResultSet rs, int n) throws SQLException {
        String status = rs.getString("status");
        String failureCode = rs.getString("failure_code");
        return new ApplicationModels.Delivery(rs.getString("id"), rs.getString("application_id"),
            rs.getString("output_id"), rs.getString("output_name"), rs.getString("fact_id"), rs.getString("device_id"),
            rs.getString("device_name"), rs.getString("product_version_id"), rs.getString("source_platform"),
            rs.getString("source_product_kind"), rs.getString("source_product_id"), rs.getString("source_driver_key"),
            rs.getString("source_product_digest"), rs.getString("authorization_reference_kind"),
            rs.getString("authorization_reference_key"), rs.getString("source_message_id"),
            rs.getString("source_event_type"), rs.getObject("source_event_at", OffsetDateTime.class),
            rs.getObject("received_at", OffsetDateTime.class), rs.getString("alarm_id"), rs.getString("mode"),
            rs.getString("protocol"), rs.getBoolean("single_device_proof"), rs.getString("content_type"),
            rs.getString("payload_sha256"), rs.getLong("payload_bytes"), status, failureCode,
            rs.getObject("http_status", Integer.class), ApplicationModels.meaning(status, failureCode),
            rs.getInt("attempt_count"), rs.getObject("captured_at", OffsetDateTime.class),
            rs.getObject("claimed_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"ARCHIVED".equals(normalized)) throw ApiError.bad("应用状态筛选无效");
        return normalized;
    }

    private static String deliveryStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("PENDING", "IN_FLIGHT", "ACCEPTED", "REJECTED", "UNKNOWN", "BLOCKED").contains(normalized))
            throw ApiError.bad("投递状态筛选无效");
        return normalized;
    }
}
