package com.fireplatform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.deviceservice.AdapterRevisionDigests;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open-application writes. Every call revalidates the current session, permission, unit
 * grant and (where the scope depends on it) the current device scope. No write activates an external effect on its
 * own: an output can only be enabled with a complete, allowlisted configuration and the raw mode additionally
 * needs the independent raw permissions. Archiving is soft and cascades to disable outputs and revoke grants.
 */
@Service
public class ApplicationCommands {
    private static final String APP_CREATE = "INSERT INTO output_applications(id,tenant_id,name,description,status,"
        + "revision,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,'ACTIVE',0,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final TenantFence tenants;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final TransactionRequirements transactions;
    private final DeviceAccessScope devices;
    private final ApplicationQueries queries;
    private final ApplicationSecretStorage secrets;
    private final ApplicationEndpointPolicy endpoints;
    private final DatabaseTime time;
    private final ObjectMapper json;
    private final List<com.fireplatform.deviceservice.IntegrationDriver> drivers;
    private final AdapterRevisionDigests adapters;

    public ApplicationCommands(JdbcTemplate jdbc, ManagementGuard guard, TenantFence tenants, IdempotencyStore idem,
                               AuditService audit, TransactionRequirements transactions, DeviceAccessScope devices,
                               ApplicationQueries queries, ApplicationSecretStorage secrets,
                               ApplicationEndpointPolicy endpoints, DatabaseTime time, ObjectMapper json,
                               List<com.fireplatform.deviceservice.IntegrationDriver> drivers,
                               AdapterRevisionDigests adapters) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.tenants = tenants;
        this.idem = idem;
        this.audit = audit;
        this.transactions = transactions;
        this.devices = devices;
        this.queries = queries;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.time = time;
        this.json = json;
        this.drivers = drivers == null ? List.of() : List.copyOf(drivers);
        this.adapters = adapters;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail create(AuthService.Identity actor, String tenant, String key,
                                                      ApplicationInputs.Create input, String requestId) {
        begin(actor, tenant, key, "applications:write");
        String operation = "app-create";
        String hash = idem.fingerprint(fingerprint(tenant, input.name(), input.description(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = time.now();
        try {
            jdbc.update(APP_CREATE, id, tenant, input.name(), input.description(), actor.user().id(), now,
                actor.user().id(), now);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.conflict("APPLICATION_NAME_TAKEN", "同一单位已存在同名应用");
        }
        event(tenant, id, "APPLICATION", id, "CREATE", null, 0, actor.user().id(), input.reason(), Map.of(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_CREATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail update(AuthService.Identity actor, String tenant, String id, String key,
                                                      ApplicationInputs.Update input, String requestId) {
        begin(actor, tenant, key, "applications:write");
        String operation = "app-update";
        String hash = idem.fingerprint(fingerprint(tenant, id, input.name(), input.description(),
            input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        requireActive(tenant, id);
        OffsetDateTime now = time.now();
        try {
            int changed = jdbc.update("UPDATE output_applications SET name=?,description=?,revision=revision+1,"
                    + "updated_by=?,updated_at=? WHERE tenant_id=? AND id=? AND revision=? AND status='ACTIVE'",
                input.name(), input.description(), actor.user().id(), now, tenant, id, input.expectedRevision());
            if (changed != 1) throw revisionConflict(tenant, id);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.conflict("APPLICATION_NAME_TAKEN", "同一单位已存在同名应用");
        }
        event(tenant, id, "APPLICATION", id, "UPDATE", input.expectedRevision(), input.expectedRevision() + 1,
            actor.user().id(), input.reason(), Map.of(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_UPDATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail archive(AuthService.Identity actor, String tenant, String id, String key,
                                                       ApplicationInputs.Archive input, String requestId) {
        begin(actor, tenant, key, "applications:write");
        String operation = "app-archive";
        String hash = idem.fingerprint(fingerprint(tenant, id, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        requireActive(tenant, id);
        OffsetDateTime now = time.now();
        int changed = jdbc.update("UPDATE output_applications SET status='ARCHIVED',archived_by=?,archived_at=?,"
                + "archived_reason=?,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE tenant_id=? AND id=? AND revision=? AND status='ACTIVE'",
            actor.user().id(), now, input.reason(), actor.user().id(), now, tenant, id, input.expectedRevision());
        if (changed != 1) throw revisionConflict(tenant, id);
        long revision = input.expectedRevision() + 1;
        event(tenant, id, "APPLICATION", id, "ARCHIVE", input.expectedRevision(), revision, actor.user().id(),
            input.reason(), Map.of(), now);
        // Soft cascade: an archived application can never leave a sendable output or an active grant behind.
        var outputs = jdbc.query("SELECT id,revision FROM application_outputs WHERE tenant_id=? AND application_id=?"
                + " AND enabled=TRUE ORDER BY id",
            (rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)), tenant, id);
        for (var output : outputs) {
            jdbc.update("UPDATE application_outputs SET enabled=FALSE,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE tenant_id=? AND id=?", actor.user().id(), now, tenant, output.getKey());
            event(tenant, id, "OUTPUT", output.getKey(), "DISABLE", output.getValue(), output.getValue() + 1,
                actor.user().id(), input.reason(), Map.of("cause", "APPLICATION_ARCHIVED"), now);
        }
        var grants = jdbc.query("SELECT id,revision FROM application_product_authorizations"
                + " WHERE tenant_id=? AND application_id=? AND status='ACTIVE' ORDER BY id",
            (rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)), tenant, id);
        for (var grant : grants) {
            jdbc.update("UPDATE application_product_authorizations SET status='REVOKED',revoked_by=?,revoked_at=?,"
                    + "revoked_reason=?,revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND id=?",
                actor.user().id(), now, input.reason(), actor.user().id(), now, tenant, grant.getKey());
            event(tenant, id, "AUTHORIZATION", grant.getKey(), "REVOKE", grant.getValue(), grant.getValue() + 1,
                actor.user().id(), input.reason(), Map.of("cause", "APPLICATION_ARCHIVED"), now);
        }
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_ARCHIVE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail grant(AuthService.Identity actor, String tenant, String id, String key,
                                                     ApplicationInputs.Grant input, String requestId) {
        begin(actor, tenant, key, "applications:write");
        String operation = "app-grant";
        var deviceIds = new ArrayList<>(input.deviceIds());
        java.util.Collections.sort(deviceIds);
        Reference reference = resolveReference(tenant, input);
        String hash = idem.fingerprint(fingerprint(tenant, id, reference.kind(), reference.key(), deviceIds,
            input.scopeMode(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        requireActive(tenant, id);
        OffsetDateTime now = time.now();
        var captured = new LinkedHashMap<String, String>();
        if ("ALL_TENANT".equals(input.scopeMode())) {
            // Whole-unit authority is still the current ALL_TENANT device scope, never an inferred default.
            devices.requireAll(actor.user().id(), tenant);
        } else {
            for (String deviceId : deviceIds) {
                String version = deviceVersionForGrant(tenant, deviceId);
                if ("FORMAL_VERSION".equals(reference.kind()) && !reference.productVersionId().equals(version))
                    throw ApiError.bad("设备当前产品版本与授权版本不一致：" + deviceId);
                devices.requireDevice(actor.user().id(), tenant, deviceId);
                captured.put(deviceId, version);
            }
        }
        var existing = jdbc.query("SELECT id,status,revision FROM application_product_authorizations"
                + " WHERE tenant_id=? AND application_id=? AND reference_key=?",
            (rs, n) -> Map.entry(rs.getString(1), new Object[]{rs.getString(2), rs.getLong(3)}), tenant, id,
            reference.key());
        String authorizationId;
        String action;
        Long before = null;
        long after;
        if (existing.isEmpty()) {
            authorizationId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO application_product_authorizations(id,tenant_id,application_id,reference_kind,"
                    + "reference_key,product_id,product_version_id,source_platform,legacy_product_id,"
                    + "legacy_decoder_revision,legacy_decoder_sha256,vendor_model_id,driver_key,adapter_digest,"
                    + "scope_mode,status,revision,created_by,created_at,updated_by,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?,?,?)",
                authorizationId, tenant, id, reference.kind(), reference.key(), reference.productId(),
                reference.productVersionId(), reference.sourcePlatform(), reference.legacyProductId(),
                reference.decoderRevision(), reference.decoderSha256(), reference.vendorModelId(),
                reference.driverKey(), reference.adapterDigest(), input.scopeMode(), actor.user().id(), now,
                actor.user().id(), now);
            action = "AUTHORIZATION_GRANT";
            after = 0;
        } else {
            authorizationId = existing.getFirst().getKey();
            Object[] state = existing.getFirst().getValue();
            if ("ACTIVE".equals(state[0])) throw ApiError.conflict("AUTHORIZATION_EXISTS", "该身份已授权");
            long revision = (Long) state[1];
            jdbc.update("UPDATE application_product_authorizations SET product_id=?,product_version_id=?,"
                    + "source_platform=?,legacy_product_id=?,legacy_decoder_revision=?,legacy_decoder_sha256=?,"
                    + "vendor_model_id=?,driver_key=?,adapter_digest=?,scope_mode=?,status='ACTIVE',"
                    + "revoked_by=NULL,revoked_at=NULL,revoked_reason=NULL,revision=revision+1,updated_by=?,updated_at=?"
                    + " WHERE tenant_id=? AND id=?",
                reference.productId(), reference.productVersionId(), reference.sourcePlatform(),
                reference.legacyProductId(), reference.decoderRevision(), reference.decoderSha256(),
                reference.vendorModelId(), reference.driverKey(), reference.adapterDigest(), input.scopeMode(),
                actor.user().id(), now, tenant, authorizationId);
            jdbc.update("DELETE FROM application_authorization_devices WHERE authorization_id=? AND tenant_id=?",
                authorizationId, tenant);
            action = "AUTHORIZATION_REGRANT";
            before = revision;
            after = revision + 1;
        }
        for (var entry : captured.entrySet()) {
            jdbc.update("INSERT INTO application_authorization_devices(authorization_id,tenant_id,device_id,"
                    + "captured_product_version_id,captured_at) VALUES(?,?,?,?,?)",
                authorizationId, tenant, entry.getKey(), entry.getValue(), now);
        }
        event(tenant, id, "AUTHORIZATION", authorizationId, action, before, after, actor.user().id(),
            input.reason(), Map.of("reference_kind", reference.kind(), "reference_key", reference.key(),
                "scope_mode", input.scopeMode(), "device_count", captured.size()), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_AUTHORIZATION_GRANT");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail revoke(AuthService.Identity actor, String tenant, String id,
                                                      String authorizationId, String key,
                                                      ApplicationInputs.Revoke input, String requestId) {
        begin(actor, tenant, key, "applications:write");
        String operation = "app-revoke";
        String hash = idem.fingerprint(fingerprint(tenant, id, authorizationId, input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        requireActive(tenant, id);
        var rows = jdbc.query("SELECT status,revision FROM application_product_authorizations"
                + " WHERE tenant_id=? AND application_id=? AND id=?",
            (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2)}, tenant, id, authorizationId);
        if (rows.isEmpty()) throw ApiError.missing();
        Object[] state = rows.getFirst();
        if (!"ACTIVE".equals(state[0])) throw ApiError.conflict("AUTHORIZATION_REVOKED", "该授权已撤销");
        if ((Long) state[1] != input.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT", "授权已更新，请刷新后重试");
        OffsetDateTime now = time.now();
        jdbc.update("UPDATE application_product_authorizations SET status='REVOKED',revoked_by=?,revoked_at=?,"
                + "revoked_reason=?,revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",
            actor.user().id(), now, input.reason(), actor.user().id(), now, tenant, authorizationId,
            input.expectedRevision());
        event(tenant, id, "AUTHORIZATION", authorizationId, "REVOKE", input.expectedRevision(),
            input.expectedRevision() + 1, actor.user().id(), input.reason(), Map.of(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_AUTHORIZATION_REVOKE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail createOutput(AuthService.Identity actor, String tenant, String id,
                                                            String key, ApplicationInputs.CreateOutput input,
                                                            String requestId) {
        begin(actor, tenant, key, "applications:write");
        var spec = input.spec();
        requireSecretsReady(spec.endpointUrl() != null || spec.credential() != null || spec.mqttPassword() != null
            || spec.mqttUsername() != null || spec.mqttTopic() != null || spec.enabled());
        if (!"NORMALIZED".equals(spec.mode())) requireRawAuthority(actor);
        String operation = "app-output-create";
        String hash = idem.fingerprint(fingerprint(tenant, id, spec.name(), spec.mode(), spec.protocol(),
            spec.endpointUrl(), spec.authScheme(), spec.authHeaderName(),
            spec.credential() == null ? null : "PROVIDED", spec.mqttTopic(), spec.mqttUsername(),
            spec.mqttPassword() == null ? null : "PROVIDED", spec.timeoutMs(), spec.enabled(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        requireActive(tenant, id);
        String outputId = UUID.randomUUID().toString();
        OffsetDateTime now = time.now();
        var configured = configure(tenant, outputId, spec, null);
        requireSendable(configured, spec.enabled());
        try {
            jdbc.update("INSERT INTO application_outputs(id,tenant_id,application_id,name,mode,protocol,enabled,"
                    + "endpoint_url_cipher,endpoint_url_nonce,endpoint_origin,endpoint_digest,endpoint_masked,"
                    + "auth_scheme,auth_header_name,credential_cipher,credential_nonce,credential_masked,"
                    + "credential_digest,mqtt_auth_scheme,mqtt_topic_cipher,mqtt_topic_nonce,mqtt_topic_masked,"
                    + "mqtt_topic_digest,mqtt_username_cipher,mqtt_username_nonce,mqtt_username_masked,"
                    + "mqtt_username_digest,mqtt_password_cipher,mqtt_password_nonce,mqtt_password_masked,"
                    + "mqtt_password_digest,timeout_ms,revision,created_by,created_at,updated_by,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                outputId, tenant, id, spec.name(), spec.mode(), spec.protocol(), spec.enabled(), configured.urlCipher,
                configured.urlNonce, configured.origin, configured.digest, configured.masked,
                configured.authScheme, configured.authHeader, configured.credentialCipher,
                configured.credentialNonce, configured.credentialMasked, configured.credentialDigest,
                configured.mqttAuthScheme, configured.mqttTopicCipher, configured.mqttTopicNonce,
                configured.mqttTopicMasked, configured.mqttTopicDigest, configured.mqttUsernameCipher,
                configured.mqttUsernameNonce, configured.mqttUsernameMasked, configured.mqttUsernameDigest,
                configured.mqttPasswordCipher, configured.mqttPasswordNonce, configured.mqttPasswordMasked,
                configured.mqttPasswordDigest, spec.timeoutMs(), actor.user().id(), now, actor.user().id(), now);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.conflict("OUTPUT_NAME_TAKEN", "同一应用已存在同名输出配置");
        }
        event(tenant, id, "OUTPUT", outputId, "CREATE", null, 0, actor.user().id(), input.reason(),
            configured.detail(), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_OUTPUT_CREATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicationModels.ApplicationDetail updateOutput(AuthService.Identity actor, String tenant, String id,
                                                            String outputId, String key,
                                                            ApplicationInputs.UpdateOutput input, String requestId) {
        begin(actor, tenant, key, "applications:write");
        var spec = input.spec();
        requireSecretsReady(spec.endpointUrl() != null || spec.credential() != null || spec.mqttPassword() != null
            || spec.mqttUsername() != null || spec.mqttTopic() != null || spec.enabled());
        String operation = "app-output-update";
        String hash = idem.fingerprint(fingerprint(tenant, id, outputId, spec.name(), spec.protocol(),
            spec.endpointUrl(), spec.authScheme(), spec.authHeaderName(),
            spec.credential() == null ? null : "PROVIDED", spec.mqttTopic(), spec.mqttUsername(),
            spec.mqttPassword() == null ? null : "PROVIDED", spec.timeoutMs(), spec.enabled(),
            input.expectedRevision(), input.reason()));
        var replay = idem.replay(actor.user().id(), operation, key, hash, ApplicationModels.ApplicationDetail.class);
        if (replay != null) return replay;
        requireActive(tenant, id);
        var rows = jdbc.query("SELECT o.mode,o.protocol,o.revision,o.endpoint_url_cipher,o.endpoint_url_nonce,"
                + "o.endpoint_origin,o.endpoint_digest,o.endpoint_masked,o.auth_scheme,o.auth_header_name,"
                + "o.credential_cipher,o.credential_nonce,o.credential_masked,o.credential_digest,"
                + "o.mqtt_auth_scheme,o.mqtt_topic_cipher,o.mqtt_topic_nonce,o.mqtt_topic_masked,o.mqtt_topic_digest,"
                + "o.mqtt_username_cipher,o.mqtt_username_nonce,o.mqtt_username_masked,o.mqtt_username_digest,"
                + "o.mqtt_password_cipher,o.mqtt_password_nonce,o.mqtt_password_masked,o.mqtt_password_digest"
                + " FROM application_outputs o WHERE o.tenant_id=? AND o.application_id=? AND o.id=?",
            (rs, n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21),
                rs.getString(22), rs.getString(23), rs.getString(24), rs.getString(25), rs.getString(26),
                rs.getString(27)},
            tenant, id, outputId);
        if (rows.isEmpty()) throw ApiError.missing();
        var current = rows.getFirst();
        long revision = (Long) current[2];
        if (revision != input.expectedRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "输出配置已更新，请刷新后重试");
        if (!current[0].equals(spec.mode())) throw ApiError.bad("输出模式创建后不可修改；如需切换请新建输出配置");
        if (!current[1].equals(spec.protocol())) throw ApiError.bad("输出协议创建后不可修改；如需切换请新建输出配置");
        if (!"NORMALIZED".equals(spec.mode()) && spec.enabled()) requireRawAuthority(actor);
        var previous = new Stored((String) current[3], (String) current[4], (String) current[5], (String) current[6],
            (String) current[7], (String) current[8], (String) current[9], (String) current[10], (String) current[11],
            (String) current[12], (String) current[13], (String) current[14], (String) current[15],
            (String) current[16], (String) current[17], (String) current[18], (String) current[19],
            (String) current[20], (String) current[21], (String) current[22], (String) current[23],
            (String) current[24], (String) current[25], (String) current[26]);
        var configured = configure(tenant, outputId, spec, previous);
        requireSendable(configured, spec.enabled());
        OffsetDateTime now = time.now();
        boolean rotated = spec.credential() != null || spec.mqttPassword() != null;
        int changed = jdbc.update("UPDATE application_outputs SET name=?,enabled=?,endpoint_url_cipher=?,endpoint_url_nonce=?,"
                + "endpoint_origin=?,endpoint_digest=?,endpoint_masked=?,auth_scheme=?,auth_header_name=?,"
                + "credential_cipher=?,credential_nonce=?,credential_masked=?,credential_digest=?,"
                + "mqtt_auth_scheme=?,mqtt_topic_cipher=?,mqtt_topic_nonce=?,mqtt_topic_masked=?,mqtt_topic_digest=?,"
                + "mqtt_username_cipher=?,mqtt_username_nonce=?,mqtt_username_masked=?,mqtt_username_digest=?,"
                + "mqtt_password_cipher=?,mqtt_password_nonce=?,mqtt_password_masked=?,mqtt_password_digest=?,"
                + "timeout_ms=?,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE tenant_id=? AND id=? AND revision=?",
            spec.name(), spec.enabled(), configured.urlCipher, configured.urlNonce, configured.origin,
            configured.digest, configured.masked, configured.authScheme, configured.authHeader,
            configured.credentialCipher, configured.credentialNonce, configured.credentialMasked,
            configured.credentialDigest, configured.mqttAuthScheme, configured.mqttTopicCipher,
            configured.mqttTopicNonce, configured.mqttTopicMasked, configured.mqttTopicDigest,
            configured.mqttUsernameCipher, configured.mqttUsernameNonce, configured.mqttUsernameMasked,
            configured.mqttUsernameDigest, configured.mqttPasswordCipher, configured.mqttPasswordNonce,
            configured.mqttPasswordMasked, configured.mqttPasswordDigest, spec.timeoutMs(),
            actor.user().id(), now, tenant, outputId, revision);
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "输出配置已更新，请刷新后重试");
        event(tenant, id, "OUTPUT", outputId, "UPDATE", revision, revision + 1, actor.user().id(), input.reason(),
            configured.detail(), now);
        if (rotated) event(tenant, id, "CREDENTIAL", outputId, "ROTATE", revision, revision + 1, actor.user().id(),
            input.reason(), Map.of("credential_masked", configured.credentialMasked == null
                ? configured.mqttPasswordMasked == null ? "" : configured.mqttPasswordMasked
                : configured.credentialMasked), now);
        return saved(actor, tenant, id, operation, key, hash, requestId, "APPLICATION_OUTPUT_UPDATE");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private record Stored(String urlCipher, String urlNonce, String origin, String digest, String masked,
                          String authScheme, String authHeader, String credentialCipher, String credentialNonce,
                          String credentialMasked, String credentialDigest, String mqttAuthScheme,
                          String mqttTopicCipher, String mqttTopicNonce, String mqttTopicMasked,
                          String mqttTopicDigest, String mqttUsernameCipher, String mqttUsernameNonce,
                          String mqttUsernameMasked, String mqttUsernameDigest, String mqttPasswordCipher,
                          String mqttPasswordNonce, String mqttPasswordMasked, String mqttPasswordDigest) { }

    private record Configured(String protocol, String urlCipher, String urlNonce, String origin, String digest,
                              String masked, String authScheme, String authHeader, String credentialCipher,
                              String credentialNonce, String credentialMasked, String credentialDigest,
                              String mqttAuthScheme, String mqttTopicCipher, String mqttTopicNonce,
                              String mqttTopicMasked, String mqttTopicDigest, String mqttUsernameCipher,
                              String mqttUsernameNonce, String mqttUsernameMasked, String mqttUsernameDigest,
                              String mqttPasswordCipher, String mqttPasswordNonce, String mqttPasswordMasked,
                              String mqttPasswordDigest) {
        Map<String, Object> detail() {
            var value = new LinkedHashMap<String, Object>();
            value.put("protocol", protocol);
            value.put("origin", origin);
            value.put("masked", masked);
            value.put("digest", digest);
            value.put("auth_scheme", authScheme);
            value.put("credential_masked", credentialMasked);
            value.put("mqtt_auth_scheme", mqttAuthScheme);
            value.put("mqtt_topic_masked", mqttTopicMasked);
            value.put("mqtt_username_masked", mqttUsernameMasked);
            value.put("mqtt_password_masked", mqttPasswordMasked);
            return value;
        }
    }

    /**
     * Configures exactly one transport. HTTP keeps its existing URL/credential semantics; MQTT stores an mqtts URL,
     * a fixed topic and username/password under separate scopes and never keeps an HTTP credential. A preserved
     * topic is revealed only to recompute the frozen digest, never to a projection.
     */
    private Configured configure(String tenant, String outputId, ApplicationInputs.OutputSpec spec, Stored previous) {
        boolean mqtt = "MQTT_TLS_3_1_1".equals(spec.protocol());
        String urlCipher = previous == null ? null : previous.urlCipher();
        String urlNonce = previous == null ? null : previous.urlNonce();
        String origin = previous == null ? null : previous.origin();
        String digest = previous == null ? null : previous.digest();
        String masked = previous == null ? null : previous.masked();
        String authScheme = previous == null ? "NONE" : previous.authScheme();
        String authHeader = previous == null ? null : previous.authHeader();
        String credentialCipher = previous == null ? null : previous.credentialCipher();
        String credentialNonce = previous == null ? null : previous.credentialNonce();
        String credentialMasked = previous == null ? null : previous.credentialMasked();
        String credentialDigest = previous == null ? null : previous.credentialDigest();
        String mqttAuthScheme = previous == null ? "NONE" : previous.mqttAuthScheme();
        String topicCipher = previous == null ? null : previous.mqttTopicCipher();
        String topicNonce = previous == null ? null : previous.mqttTopicNonce();
        String topicMasked = previous == null ? null : previous.mqttTopicMasked();
        String topicDigest = previous == null ? null : previous.mqttTopicDigest();
        String usernameCipher = previous == null ? null : previous.mqttUsernameCipher();
        String usernameNonce = previous == null ? null : previous.mqttUsernameNonce();
        String usernameMasked = previous == null ? null : previous.mqttUsernameMasked();
        String usernameDigest = previous == null ? null : previous.mqttUsernameDigest();
        String passwordCipher = previous == null ? null : previous.mqttPasswordCipher();
        String passwordNonce = previous == null ? null : previous.mqttPasswordNonce();
        String passwordMasked = previous == null ? null : previous.mqttPasswordMasked();
        String passwordDigest = previous == null ? null : previous.mqttPasswordDigest();

        if (mqtt) {
            authScheme = "NONE";
            authHeader = null;
            credentialCipher = null;
            credentialNonce = null;
            credentialMasked = null;
            credentialDigest = null;
            mqttAuthScheme = "USERNAME_PASSWORD";
            String topic = spec.mqttTopic();
            if (topic == null && spec.endpointUrl() != null && previous != null && previous.mqttTopicCipher() != null) {
                topic = secrets.reveal(ApplicationSecretStorage.mqttTopicScope(tenant, outputId),
                    previous.mqttTopicCipher(), previous.mqttTopicNonce());
            }
            if (spec.endpointUrl() != null) {
                if (topic == null) throw ApiError.bad("配置MQTT地址时必须同时提供主题");
                var validated = endpoints.validateMqtt(spec.endpointUrl(), topic);
                var protectedUrl = secrets.protect(ApplicationSecretStorage.urlScope(tenant, outputId), validated.url());
                urlCipher = protectedUrl.cipher();
                urlNonce = protectedUrl.nonce();
                origin = validated.origin();
                digest = validated.digest();
                masked = validated.masked();
            }
            if (spec.mqttTopic() != null) {
                var protectedTopic = secrets.protect(ApplicationSecretStorage.mqttTopicScope(tenant, outputId),
                    spec.mqttTopic());
                topicCipher = protectedTopic.cipher();
                topicNonce = protectedTopic.nonce();
                topicMasked = ApplicationSecretStorage.mask(spec.mqttTopic());
                topicDigest = Digests.sha256(spec.mqttTopic());
                if (origin != null) digest = ApplicationEndpointPolicy.mqttDigest(origin, spec.mqttTopic());
            }
            if (spec.mqttUsername() != null) {
                var protectedUsername = secrets.protect(ApplicationSecretStorage.mqttUsernameScope(tenant, outputId),
                    spec.mqttUsername());
                usernameCipher = protectedUsername.cipher();
                usernameNonce = protectedUsername.nonce();
                usernameMasked = ApplicationSecretStorage.mask(spec.mqttUsername());
                usernameDigest = Digests.sha256(spec.mqttUsername());
            }
            if (spec.mqttPassword() != null) {
                var protectedPassword = secrets.protect(ApplicationSecretStorage.mqttPasswordScope(tenant, outputId),
                    spec.mqttPassword());
                passwordCipher = protectedPassword.cipher();
                passwordNonce = protectedPassword.nonce();
                passwordMasked = ApplicationSecretStorage.mask(spec.mqttPassword());
                passwordDigest = Digests.sha256(spec.mqttPassword());
            }
        } else {
            mqttAuthScheme = "NONE";
            topicCipher = null;
            topicNonce = null;
            topicMasked = null;
            topicDigest = null;
            usernameCipher = null;
            usernameNonce = null;
            usernameMasked = null;
            usernameDigest = null;
            passwordCipher = null;
            passwordNonce = null;
            passwordMasked = null;
            passwordDigest = null;
            String scheme = spec.authScheme() != null ? spec.authScheme() : previous == null ? "NONE" : previous.authScheme();
            authScheme = scheme;
            authHeader = "HEADER".equals(scheme)
                ? (spec.authHeaderName() != null ? spec.authHeaderName() : previous == null ? null : previous.authHeader())
                : null;
            if ("HEADER".equals(scheme) && authHeader == null)
                throw ApiError.bad("请求头认证必须提供请求头名称");
            String credential = spec.credential();
            if ("NONE".equals(scheme) && credential != null)
                throw ApiError.bad("认证方式为NONE时不能提交凭据；请选择认证方式或清空凭据");
            if (!"NONE".equals(scheme) && credential == null && (previous == null || previous.credentialCipher() == null))
                throw ApiError.bad("当前认证方式必须提供凭据");
            if ("BASIC".equals(scheme) && credential != null
                && (credential.indexOf(':') <= 0 || credential.indexOf(':') == credential.length() - 1))
                throw ApiError.bad("基础认证凭据必须为用户名:密码");
            if (spec.endpointUrl() != null) {
                var validated = endpoints.validate(spec.endpointUrl());
                var protectedUrl = secrets.protect(ApplicationSecretStorage.urlScope(tenant, outputId), validated.url());
                urlCipher = protectedUrl.cipher();
                urlNonce = protectedUrl.nonce();
                origin = validated.origin();
                digest = validated.digest();
                masked = validated.masked();
            }
            if ("NONE".equals(scheme)) {
                credentialCipher = null;
                credentialNonce = null;
                credentialMasked = null;
                credentialDigest = null;
            } else if (credential != null) {
                var protectedCredential = secrets.protect(ApplicationSecretStorage.credentialScope(tenant, outputId), credential);
                credentialCipher = protectedCredential.cipher();
                credentialNonce = protectedCredential.nonce();
                credentialMasked = ApplicationSecretStorage.mask(credential);
                credentialDigest = Digests.sha256(credential);
            }
        }
        return new Configured(spec.protocol(), urlCipher, urlNonce, origin, digest, masked, authScheme, authHeader,
            credentialCipher, credentialNonce, credentialMasked, credentialDigest, mqttAuthScheme, topicCipher,
            topicNonce, topicMasked, topicDigest, usernameCipher, usernameNonce, usernameMasked, usernameDigest,
            passwordCipher, passwordNonce, passwordMasked, passwordDigest);
    }

    private static void requireSendable(Configured configured, boolean enabled) {
        if (!enabled) return;
        if (configured.urlCipher() == null) throw ApiError.bad("启用输出前必须配置输出地址");
        if ("MQTT_TLS_3_1_1".equals(configured.protocol())) {
            if (configured.mqttTopicCipher() == null || configured.mqttTopicNonce() == null)
                throw ApiError.bad("启用MQTT输出前必须配置主题");
            if (configured.mqttUsernameCipher() == null || configured.mqttUsernameNonce() == null
                || configured.mqttPasswordCipher() == null || configured.mqttPasswordNonce() == null)
                throw ApiError.bad("启用MQTT输出前必须配置用户名与密码；不支持匿名连接");
            if (!"USERNAME_PASSWORD".equals(configured.mqttAuthScheme()))
                throw ApiError.bad("MQTT输出认证方式必须为用户名密码");
            return;
        }
        if (!"NONE".equals(configured.authScheme()) && configured.credentialCipher() == null)
            throw ApiError.bad("启用输出前必须配置凭据");
    }

    private void requireRawAuthority(AuthService.Identity actor) {
        if (!currentPermission(actor.user().id(), "applications:forward-raw")
            || !currentPermission(actor.user().id(), "messages:raw"))
            throw ApiError.forbidden("原始报文转发需要应用原始转发权限与消息原文权限");
    }

    private boolean currentPermission(String userId, String permission) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM effective_user_permissions WHERE user_id=? AND permission=?", Long.class, userId,
            permission);
        return count != null && count == 1L;
    }

    private void requireSecretsReady(boolean required) {
        if (!required) return;
        if (!secrets.ready())
            throw new ApiError(503, "APPLICATION_OUTPUT_ENCRYPTION_UNAVAILABLE",
                "未配置 APP_APPLICATION_OUTPUT_KEY，禁止保存输出地址与凭据");
        if (!endpoints.allowlistConfigured())
            throw new ApiError(409, "ENDPOINT_ALLOWLIST_NOT_CONFIGURED",
                "未配置 APP_APPLICATION_OUTPUT_ALLOWED_HOSTS，禁止保存任何输出地址");
    }

    /** Read-only authoritative resolution, shared with metadata import preview (never writes). */
    public void validateReference(String tenant, ApplicationInputs.Grant input) {
        resolveReference(tenant, input);
    }

    private record Reference(String kind, String key, String productId, String productVersionId,
                             String sourcePlatform, String legacyProductId, Long decoderRevision,
                             String decoderSha256, String vendorModelId, String driverKey, String adapterDigest) { }

    /**
     * Resolves exactly one authoritative identity for the requested kind. Every value is proven against existing
     * registries/source metadata; nothing is inferred from a name, a prefix or the first row of a list.
     */
    private Reference resolveReference(String tenant, ApplicationInputs.Grant input) {
        return switch (input.referenceKind()) {
            case "FORMAL_VERSION" -> {
                requirePublishedVersion(input.productId(), input.productVersionId());
                yield new Reference("FORMAL_VERSION", "FORMAL:" + input.productVersionId(), input.productId(),
                    input.productVersionId(), null, null, null, null, null, null, null);
            }
            case "LEGACY_PRODUCT" -> {
                Long observed = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_messages"
                        + " WHERE tenant_id=? AND vendor_platform=? AND vendor_platform<>'hikvision_firecloud'",
                    Long.class, tenant, input.sourcePlatform());
                if (observed == null || observed == 0L)
                    throw new ApiError(409, "SOURCE_PLATFORM_NOT_OBSERVED",
                        "该单位尚无该来源平台的观测报文，不能建立旧产品授权");
                var decoder = jdbc.query("SELECT revision,source_sha256 FROM legacy_product_decoders"
                        + " WHERE legacy_product_id=? ORDER BY revision DESC LIMIT 1",
                    (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, input.legacyProductId());
                if (decoder.isEmpty())
                    throw new ApiError(409, "LEGACY_PRODUCT_UNKNOWN", "旧产品解码器注册表中不存在该旧产品ID");
                Object[] row = decoder.getFirst();
                yield new Reference("LEGACY_PRODUCT",
                    "LEGACY:" + input.sourcePlatform() + ":" + input.legacyProductId(), null, null,
                    input.sourcePlatform(), input.legacyProductId(), (Long) row[0], (String) row[1], null, null, null);
            }
            case "VENDOR_MODEL" -> {
                if (!"hikvision_firecloud".equals(input.sourcePlatform()))
                    throw new ApiError(409, "SOURCE_PLATFORM_UNSUPPORTED",
                        "当前只有海康发现元数据可作为厂商型号授权来源");
                if (input.driverKey() == null)
                    throw ApiError.bad("厂商型号授权必须提供来源适配器");
                String driver = knownDriver(input.driverKey());
                String adapterDigest = adapters.digestFor(driver);
                if (adapterDigest == null)
                    throw new ApiError(409, "ADAPTER_DIGEST_UNAVAILABLE",
                        "缺少该来源适配器的权威摘要证据，不能建立型号授权");
                Long evidenced = jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices h"
                    + " JOIN devices d ON d.id=h.local_device_id WHERE d.tenant_id=? AND h.model=?",
                    Long.class, tenant, input.vendorModelId());
                if (evidenced == null || evidenced == 0L)
                    throw new ApiError(409, "VENDOR_MODEL_UNKNOWN",
                        "该单位发现元数据中不存在该厂商型号，不能建立型号授权");
                yield new Reference("VENDOR_MODEL", "VENDOR:" + input.sourcePlatform() + ":" + input.vendorModelId(),
                    null, null, input.sourcePlatform(), null, null, null, input.vendorModelId(), driver,
                    adapterDigest);
            }
            default -> throw ApiError.bad("授权身份类型无效");
        };
    }

    private String knownDriver(String requested) {
        for (var driver : drivers) if (driver.key().equalsIgnoreCase(requested)) return driver.key();
        throw new ApiError(409, "DRIVER_UNKNOWN", "未找到该来源适配器，不能建立型号授权");
    }

    private void requirePublishedVersion(String productId, String versionId) {
        Long product = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id=?", Long.class, productId);
        if (product == null || product != 1L) throw ApiError.bad("产品不存在");
        Long version = jdbc.queryForObject("SELECT COUNT(*) FROM product_versions WHERE id=? AND product_id=?"
            + " AND status='PUBLISHED'", Long.class, versionId, productId);
        if (version == null || version != 1L) throw ApiError.bad("授权必须指向该产品已发布的版本");
    }

    /**
     * Authoritative asset context at grant time. A missing formal version is normal for approved unversioned
     * legacy/vendor assets, so only the kind-specific rule decides whether that is acceptable.
     */
    private String deviceVersionForGrant(String tenant, String deviceId) {
        var rows = jdbc.queryForList("SELECT product_version_id FROM devices WHERE tenant_id=? AND id=?"
            + " AND archived_at IS NULL AND is_simulated=FALSE", String.class, tenant, deviceId);
        if (rows.isEmpty()) throw ApiError.bad("设备不存在、已归档或为仿真设备：" + deviceId);
        return rows.getFirst();
    }

    private void requireActive(String tenant, String id) {
        var rows = jdbc.queryForList("SELECT status FROM output_applications WHERE tenant_id=? AND id=?", String.class,
            tenant, id);
        if (rows.isEmpty()) throw ApiError.missing();
        if (!"ACTIVE".equals(rows.getFirst())) throw ApiError.conflict("APPLICATION_ARCHIVED", "应用已归档，只读");
    }

    private ApiError revisionConflict(String tenant, String id) {
        var rows = jdbc.queryForList("SELECT status FROM output_applications WHERE tenant_id=? AND id=?", String.class,
            tenant, id);
        if (rows.isEmpty()) return ApiError.missing();
        if (!"ACTIVE".equals(rows.getFirst())) return ApiError.conflict("APPLICATION_ARCHIVED", "应用已归档，只读");
        return ApiError.conflict("REVISION_CONFLICT", "应用已更新，请刷新后重试");
    }

    private void begin(AuthService.Identity actor, String tenant, String key, String permission) {
        transactions.readCommitted();
        guard.begin(actor, permission, tenant, actor.audience());
        tenants.lockEnabled(tenant);
        idem.validateKey(key);
    }

    private ApplicationModels.ApplicationDetail saved(AuthService.Identity actor, String tenant, String id,
                                                      String operation, String key, String hash, String requestId,
                                                      String auditAction) {
        var detail = queries.current(tenant, id);
        audit.record(actor.user().id(), tenant, auditAction, id, "SUCCESS", requestId);
        idem.save(actor.user().id(), operation, key, hash, detail);
        return detail;
    }

    private void event(String tenant, String applicationId, String subjectType, String subjectId, String action,
                       Long revisionBefore, long revisionAfter, String actorId, String reason,
                       Map<String, ?> detail, OffsetDateTime now) {
        String encoded;
        try {
            encoded = json.writeValueAsString(detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode application history detail", failure);
        }
        jdbc.update("INSERT INTO output_application_events(id,tenant_id,application_id,subject_type,subject_id,action,"
                + "revision_before,revision_after,actor_id,reason,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), tenant, applicationId, subjectType, subjectId, action, revisionBefore,
            revisionAfter, actorId, reason, encoded, now);
    }

    private Map<String, Object> fingerprint(Object... values) {
        var value = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index++) value.put("v" + index, values[index]);
        return value;
    }
}
