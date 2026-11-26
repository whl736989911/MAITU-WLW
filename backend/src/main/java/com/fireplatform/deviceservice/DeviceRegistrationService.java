package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.product.PublishedVersionCatalog;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Preregistration and commissioning. Cloud acceptance never finalizes a local asset by itself, and an
 * existing cloud resource is only linked to an explicitly selected authorized local device. No
 * confirmRight call is made because the official portal does not publish it as an association API.
 */
@Service
public class DeviceRegistrationService {
    private static final String READ_GLOBAL = "device-registrations:read-global";
    private static final String WRITE_GLOBAL = "device-registrations:write-global";
    private record Registration(String id, String driverKey, String mode, String status, long revision,
                                String tenantId, String placeId, String productVersionId, String deviceId,
                                String externalDeviceId, String externalSerial, String deviceName, String deviceModel,
                                Integer deviceType, Integer communicationType, Integer accessMode, Integer producterId,
                                Integer productModelId, String vendorUnitId, String verificationCipher,
                                String verificationNonce, String vendorPayloadCipher, String location,
                                java.math.BigDecimal latitude, java.math.BigDecimal longitude, Boolean autoAddChannel,
                                String accountFingerprint, String cloudState, String cloudResultJson, String localState,
                                String acceptanceNote, String createdBy, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt, OffsetDateTime commissionedAt) { }
    private record Pending(Registration registration, VendorLifecycleDriver driver, String verificationCode,
                           String accountFingerprint, String linkedExternalId, boolean importedLedger) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DriverRegistry drivers;
    private final DeviceServiceSecrets secrets;
    private final DeviceSourceBindingService bindings;
    private final PublishedVersionCatalog catalog;
    private final ImportedLegacyAssetMatcher importedAssets;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final DeviceAccessScope scopes;
    private final AuditService audit;
    private final IdempotencyStore idem;
    private final PageSizePolicy sizes;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public DeviceRegistrationService(JdbcTemplate jdbc, ObjectMapper json, DriverRegistry drivers,
                                     DeviceServiceSecrets secrets, DeviceSourceBindingService bindings, PublishedVersionCatalog catalog,
                                     ImportedLegacyAssetMatcher importedAssets,
                                     ManagementGuard guard, CoordinationFence coordination, DeviceAccessScope scopes,
                                     AuditService audit, IdempotencyStore idem, PageSizePolicy sizes,
                                     Clock clock, ApplicationEventPublisher events, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.drivers = drivers;
        this.secrets = secrets;
        this.bindings = bindings;
        this.catalog = catalog;
        this.importedAssets = importedAssets;
        this.guard = guard;
        this.coordination = coordination;
        this.scopes = scopes;
        this.audit = audit;
        this.idem = idem;
        this.sizes = sizes;
        this.clock = clock;
        this.events = events;
        this.transactions = transactions;
    }

    // ---------------- create ----------------

    @Transactional
    public DeviceServiceModels.RegistrationDetail create(AuthService.Identity actor, String headerTenant, String key,
                                                         DeviceServiceInputs.CreateRegistration input, String requestId) {
        return createInternal(actor, headerTenant, "management", false, key, input, requestId);
    }

    /** Customer self-service: unit is fixed by the session; no unassigned pool or global keys. */
    @Transactional
    public DeviceServiceModels.CustomerRegistrationDetail createForCustomer(AuthService.Identity actor, String key,
                                                                          DeviceServiceInputs.CreateRegistration input, String requestId) {
        String tenant = actor.user().tenantId();
        if (tenant == null || tenant.isBlank()) throw ApiError.forbidden("客户账号缺少所属单位");
        if (input.tenantId() != null && !input.tenantId().isBlank() && !tenant.equals(input.tenantId()))
            throw ApiError.forbidden("客户只能在本单位创建来源登记");
        if (!"NEW_VENDOR".equals(input.mode()) && !"MATCH_EXISTING".equals(input.mode()))
            throw ApiError.conflict("CUSTOMER_REGISTRATION_MODE", "客户仅支持厂商新增或匹配本单位已关联来源");
        if ("NEW_VENDOR".equals(input.mode())) requireCustomerNewVendor(actor, tenant);
        var created = createInternal(actor, tenant, "customer", true, key, input, requestId);
        return customerDetail(created.registration().id());
    }

    private DeviceServiceModels.RegistrationDetail createInternal(AuthService.Identity actor, String headerTenant,
                                                                   String audience, boolean customer, String key,
                                                                   DeviceServiceInputs.CreateRegistration input, String requestId) {
        coordination.lock();
        String tenant = customer ? headerTenant : (input.tenantId() == null || input.tenantId().isBlank() ? headerTenant : input.tenantId());
        if (headerTenant != null && tenant != null && !headerTenant.equals(tenant))
            throw ApiError.bad("请求单位与请求头单位不一致");
        guard.begin(actor, "device:write", tenant, audience);
        idem.validateKey(key);
        if (tenant != null) {
            if (input.placeId() != null && !input.placeId().isBlank()) {
                place(tenant, input.placeId());
                // A place target must be inside the actor's dynamic subtree; DEVICE_SET has no place authority.
                scopes.requirePlace(actor.user().id(), tenant, input.placeId());
            } else {
                scopes.requireAll(actor.user().id(), tenant);
            }
        }
        if (!input.externalSerial().matches("[\\x21-\\x7e]{1,200}")) throw ApiError.bad("设备序列号必须是1-200位安全ASCII");
        if ((input.latitude() == null) != (input.longitude() == null)) throw ApiError.bad("经纬度必须同时提供或同时留空");
        if (tenant == null && input.placeId() != null && !input.placeId().isBlank()) throw ApiError.bad("本地位置必须先确定单位，不能创建无单位归属的位置登记");
        IntegrationDriver driver = drivers.require(input.driverKey());
        boolean vendorMode = "VENDOR_PLATFORM".equals(driver.mode());
        VendorLifecycleDriver lifecycle = driver instanceof VendorLifecycleDriver value ? value : null;
        boolean importedLedger = lifecycle != null && lifecycle.supportsImportedLedgerAssociation();
        if (!vendorMode && !"PRODUCT_MODEL".equals(input.mode()))
            throw ApiError.bad("产品模型模式下只能使用PRODUCT_MODEL登记");
        if (vendorMode && "PRODUCT_MODEL".equals(input.mode()))
            throw ApiError.bad("厂商平台模式下不能使用PRODUCT_MODEL登记");
        if ("PRODUCT_MODEL".equals(input.mode())) {
            if (input.deviceName() == null || input.deviceName().isBlank()) throw ApiError.bad("产品模型登记必须填写本地设备名称");
            if (!input.externalSerial().matches("[\\x21-\\x7e]{1,64}")) throw ApiError.bad("产品模型设备标识必须是1-64位安全ASCII");
            if (input.productVersionId() == null || input.productVersionId().isBlank()) throw ApiError.bad("产品模型登记必须先明确已发布产品版本");
        }
        if ("NEW_VENDOR".equals(input.mode())) {
            if (lifecycle == null) throw ApiError.conflict("DRIVER_LIFECYCLE_UNSUPPORTED", "该驱动没有可用的云端登记操作");
            if (!importedLedger) requiredVendorFields(input);
            lifecycle.validateRegistration(vendorRegistration(input), input.vendorPayload());
            if (customer && !driver.configuration().configured())
                throw ApiError.conflict("VENDOR_NOT_CONFIGURED", "厂商接入未启用或凭证不可用，客户不能发起新增登记");
        }
        if ("MATCH_EXISTING".equals(input.mode())) {
            if (importedLedger) {
                if (tenant == null) throw ApiError.bad("关联已导入旧台账资产必须先确定本地单位");
                if (input.externalDeviceId() == null || input.externalDeviceId().isBlank())
                    throw ApiError.bad("关联已导入旧台账资产必须提供旧平台台账ID(dId)，不能用名称或首行推断");
                if (!input.externalDeviceId().matches("[\\x21-\\x7e]{1,128}"))
                    throw ApiError.bad("旧平台台账ID必须是1-128位安全ASCII");
                if (customer) customerImportedScope(actor, tenant, input.driverKey(), input.externalSerial(), input.externalDeviceId());
            } else if (customer) {
                customerLinkedSource(tenant, input.externalSerial());
            }
        }
        String vendorPayloadHash = input.vendorPayload() == null || input.vendorPayload().isNull()
            ? "none" : Digests.sha256(input.vendorPayload().toString());
        // Preserve the retired per-registration unit slot in fingerprints of existing non-Hikvision requests.
        List<String> fingerprintParts = new ArrayList<>(List.of(input.mode(), input.driverKey(), input.externalSerial(),
            String.valueOf(input.externalDeviceId()), vendorPayloadHash,
            String.valueOf(input.deviceName()), String.valueOf(input.deviceModel()), String.valueOf(input.deviceType()),
            String.valueOf(input.communicationType()), String.valueOf(input.accessMode()), String.valueOf(input.producterId()),
            String.valueOf(input.productModelId()), "null", String.valueOf(input.tenantId()),
            String.valueOf(input.placeId()), String.valueOf(input.productVersionId())));
        if ("NEW_VENDOR".equals(input.mode()) && "HIKVISION_FIRECLOUD".equals(input.driverKey())) {
            String verificationHash = input.verificationCode() == null ? "none" : Digests.sha256(input.verificationCode());
            fingerprintParts.addAll(List.of(verificationHash, String.valueOf(input.location()),
                String.valueOf(input.latitude()), String.valueOf(input.longitude()), String.valueOf(input.autoAddChannel())));
        }
        String fingerprint = idem.fingerprint(fingerprintParts);
        DeviceServiceModels.IdResult replay = idem.replay(actor.user().id(), "device-registration-create", key, fingerprint,
            DeviceServiceModels.IdResult.class);
        if (replay != null) return customer ? rawDetail(replay.id()) : detail(actor, replay.id(), false);
        if ("NEW_VENDOR".equals(input.mode()) && "HIKVISION_FIRECLOUD".equals(input.driverKey())
            && jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'",
                Long.class, input.externalSerial()) != 0)
            throw ApiError.conflict("DEVICE_EUI_CONFLICT", "设备标识已被使用");
        if ("PRODUCT_MODEL".equals(input.mode())) catalog.requireAvailableForNewUse(input.productVersionId());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM device_registrations WHERE driver_key=? AND external_serial=? AND status NOT IN ('COMMISSIONED','ABANDONED')",
                Long.class, input.driverKey(), input.externalSerial()) != 0)
            throw ApiError.conflict("REGISTRATION_EXISTS", "该序列号已存在未完成的登记；请恢复原登记而不是重复创建");
        String id = UUID.randomUUID().toString();
        var verification = secrets.protectVerification(id, input.verificationCode());
        String vendorPayload = secrets.protectVendorPayload(id, input.vendorPayload());
        boolean requiresCloud = !"PRODUCT_MODEL".equals(input.mode());
        String cloudState = requiresCloud ? "NOT_ATTEMPTED" : "NOT_REQUIRED";
        String account = driver.accountFingerprint();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.update("INSERT INTO device_registrations(id,driver_key,mode,status,revision,tenant_id,place_id,product_version_id,external_device_id,external_serial,device_name,device_model,device_type,communication_type,access_mode,producter_id,product_model_id,verification_cipher,verification_nonce,vendor_payload_cipher,location,latitude,longitude,auto_add_channel,account_fingerprint,cloud_state,local_state,created_by,created_at,updated_at) VALUES(?,?,?,'DRAFT',0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'NOT_READY',?,?,?)",
                id, input.driverKey(), input.mode(), tenant, blank(input.placeId()), blank(input.productVersionId()),
                blank(input.externalDeviceId()), input.externalSerial(), blank(input.deviceName()), blank(input.deviceModel()),
                input.deviceType(), input.communicationType(), input.accessMode(), input.producterId(), input.productModelId(),
                verification == null ? null : verification.ciphertext(),
                verification == null ? null : verification.nonce(), vendorPayload, blank(input.location()),
                input.latitude(), input.longitude(), input.autoAddChannel(), account, cloudState, actor.user().id(), now, now);
        } catch (DuplicateKeyException race) {
            throw ApiError.conflict("REGISTRATION_EXISTS", "该序列号正在创建登记，请刷新后查看");
        }
        event(id, "CREATE", input.mode(), null, actor.user().id());
        audit.recordReason(actor.user().id(), tenant, "DEVICE_REGISTRATION_CREATE", id, "SUCCESS", requestId, input.mode() + " " + input.externalSerial());
        idem.save(actor.user().id(), "device-registration-create", key, fingerprint, new DeviceServiceModels.IdResult(id));
        return customer ? rawDetail(id) : detail(actor, id, false);
    }

    private void requiredVendorFields(DeviceServiceInputs.CreateRegistration input) {
        if (input.deviceName() == null || input.deviceName().isBlank()) throw ApiError.bad("厂商新增登记必须填写设备名称");
        if (input.deviceModel() == null || input.deviceModel().isBlank()) throw ApiError.bad("厂商新增登记必须填写设备型号");
        if (input.deviceType() == null) throw ApiError.bad("厂商新增登记必须填写设备类型码");
        if (input.communicationType() == null) throw ApiError.bad("厂商新增登记必须填写通信类型码");
        if (input.deviceName().length() > 200 || input.deviceModel().length() > 100) throw ApiError.bad("设备名称或型号超过本地限制");
    }

    // ---------------- read ----------------

    public Page<DeviceServiceModels.RegistrationSummary> page(AuthService.Identity actor, String tenant, int page, Integer size) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        boolean readGlobal = guard.hasCurrentPermission(actor, READ_GLOBAL) || guard.hasCurrentPermission(actor, WRITE_GLOBAL);
        var scope = scopes.predicate(actor.user().id(), "d.tenant_id", "d.id");
        var placeScope = scopes.placePredicate(actor.user().id(), "r.tenant_id", "r.place_id");
        String visibility = "((r.tenant_id IS NULL AND (r.created_by=? OR " + (readGlobal ? "TRUE" : "FALSE") + "))"
            + " OR (r.tenant_id IS NOT NULL AND r.device_id IS NOT NULL AND EXISTS (SELECT 1 FROM devices d WHERE d.id=r.device_id AND d.tenant_id=r.tenant_id" + scope.and() + "))"
            + " OR (r.tenant_id IS NOT NULL AND r.device_id IS NULL AND EXISTS (SELECT 1 FROM user_tenant_grants g WHERE g.user_id=? AND g.tenant_id=r.tenant_id)"
            + " AND (r.created_by=? OR (r.place_id IS NOT NULL AND " + placeScope.sql() + ")"
            + " OR (r.place_id IS NULL AND EXISTS (SELECT 1 FROM user_device_scopes s WHERE s.user_id=? AND s.tenant_id=r.tenant_id AND s.mode='ALL_TENANT')))))";
        String where = " WHERE " + visibility + (tenant == null ? "" : " AND r.tenant_id=?");
        List<Object> args = new ArrayList<>();
        args.add(actor.user().id());
        args.addAll(scope.bindings());
        args.add(actor.user().id());
        args.add(actor.user().id());
        args.addAll(placeScope.bindings());
        args.add(actor.user().id());
        if (tenant != null) args.add(tenant);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM device_registrations r" + where, Long.class, args.toArray());
        args.add(pagination.size());
        args.add(pagination.offset());
        var items = jdbc.query("SELECT r.* FROM device_registrations r" + where + " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, n) -> summary(rs), args.toArray());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    /** Customer list: only same-unit rows the customer can actually see; no unassigned pool. */
    public Page<DeviceServiceModels.CustomerRegistrationSummary> pageForCustomer(AuthService.Identity actor, int page, Integer size) {
        String tenant = actor.user().tenantId();
        if (tenant == null || tenant.isBlank()) throw ApiError.forbidden("客户账号缺少所属单位");
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        var scope = scopes.predicate(actor.user().id(), "d.tenant_id", "d.id");
        var placeScope = scopes.placePredicate(actor.user().id(), "r.tenant_id", "r.place_id");
        String visibility = "(r.tenant_id=? AND ((r.device_id IS NOT NULL AND EXISTS (SELECT 1 FROM devices d WHERE d.id=r.device_id AND d.tenant_id=r.tenant_id" + scope.and() + "))"
            + " OR (r.device_id IS NULL AND (r.created_by=? OR (r.place_id IS NOT NULL AND " + placeScope.sql() + ")"
            + " OR (r.place_id IS NULL AND EXISTS (SELECT 1 FROM user_device_scopes s WHERE s.user_id=? AND s.tenant_id=r.tenant_id AND s.mode='ALL_TENANT'))))))";
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.addAll(scope.bindings());
        args.add(actor.user().id());
        args.addAll(placeScope.bindings());
        args.add(actor.user().id());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM device_registrations r WHERE " + visibility, Long.class, args.toArray());
        args.add(pagination.size());
        args.add(pagination.offset());
        var items = jdbc.query("SELECT r.* FROM device_registrations r WHERE " + visibility + " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, n) -> customerSummary(row(rs)), args.toArray());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    public DeviceServiceModels.CustomerRegistrationDetail detailForCustomer(AuthService.Identity actor, String id) {
        Registration registration = load(id);
        requireCustomerAccess(actor, registration);
        return customerDetail(registration);
    }

    public DeviceServiceModels.RegistrationDetail detail(AuthService.Identity actor, String id, boolean write) {
        Registration registration = load(id);
        requireAccess(actor, registration, write);
        return detailView(registration);
    }

    private DeviceServiceModels.RegistrationDetail detailView(Registration registration) {
        String id = registration.id();
        List<DeviceServiceModels.RegistrationEvent> events = jdbc.query("SELECT * FROM device_registration_events WHERE registration_id=? ORDER BY occurred_at,id",
            (rs, n) -> new DeviceServiceModels.RegistrationEvent(rs.getString("id"), rs.getString("step"),
                rs.getString("outcome"), parseNullable(rs.getString("detail_json")), rs.getString("actor_id"),
                rs.getObject("occurred_at", OffsetDateTime.class)), id);
        ObjectNode coordinates = json.createObjectNode();
        if (registration.latitude() != null) coordinates.put("latitude", registration.latitude()).put("longitude", registration.longitude());
        List<String> vendorFields = vendorFieldNames(registration);
        return new DeviceServiceModels.RegistrationDetail(summary(registration), registration.deviceType(),
            registration.communicationType(), registration.accessMode(), registration.producterId(),
            registration.productModelId(), registration.vendorUnitId(), registration.verificationCipher() != null,
            registration.vendorPayloadCipher() != null, vendorFields, registration.location(), coordinates,
            registration.autoAddChannel(), registration.accountFingerprint(),
            parseNullable(registration.cloudResultJson()), registration.acceptanceNote(), registration.createdBy(), events);
    }

    private List<String> vendorFieldNames(Registration registration) {
        if (registration.vendorPayloadCipher() == null) return List.of();
        try {
            JsonNode payload = secrets.revealVendorPayload(registration.id(), registration.vendorPayloadCipher());
            if (payload == null || !payload.isObject()) return List.of();
            List<String> names = new ArrayList<>();
            payload.fieldNames().forEachRemaining(names::add);
            return List.copyOf(names);
        } catch (ApiError unreadable) {
            return List.of();
        }
    }

    // ---------------- resume (vendor add or exact lookup) ----------------

    public DeviceServiceModels.RegistrationDetail resume(AuthService.Identity actor, String id,
                                                         String requestId) {
        return doResume(actor, id, requestId, "management", false);
    }

    public DeviceServiceModels.CustomerRegistrationDetail resumeForCustomer(AuthService.Identity actor, String id,
                                                                            String requestId) {
        Registration registration = load(id);
        requireCustomerAccess(actor, registration);
        if ("NEW_VENDOR".equals(registration.mode())) {
            requireCustomerNewVendor(actor, registration.tenantId());
            customerPreflightAbsent(actor, registration);
        }
        doResume(actor, id, requestId, "customer", true);
        return customerDetail(id);
    }

    private DeviceServiceModels.RegistrationDetail doResume(AuthService.Identity actor, String id,
                                                            String requestId,
                                                            String audience, boolean customer) {
        Pending pending = transactions.execute(status -> prepareResume(actor, id, audience, customer));
        if (pending == null) throw new IllegalStateException("Registration resume preparation failed");
        VendorLifecycleDriver.CloudResult cloud;
        if ("MATCH_EXISTING".equals(pending.registration().mode())) {
            VendorLifecycleDriver.LookupResult lookup;
            if (pending.importedLedger()) {
                lookup = importedLookup(pending);
            } else {
                lookup = pending.driver().lookup(pending.registration().externalSerial(),
                    pending.registration().externalDeviceId());
            }
            cloud = switch (lookup.state()) {
                case "FOUND" -> new VendorLifecycleDriver.CloudResult("SUCCEEDED", "FOUND", lookup.externalDeviceId(),
                    lookup.result(), lookup.note());
                case "NOT_FOUND", "AMBIGUOUS" -> new VendorLifecycleDriver.CloudResult("FAILED", lookup.state(), null,
                    lookup.result(), lookup.note());
                case "REJECTED" -> new VendorLifecycleDriver.CloudResult("REJECTED", "QUERY_REJECTED", null,
                    lookup.result(), lookup.note());
                default -> new VendorLifecycleDriver.CloudResult("UNKNOWN", lookup.state(), null, lookup.result(), lookup.note());
            };
        } else {
            cloud = pending.driver().register(vendorRegistration(pending.registration(), pending.verificationCode()),
                pending.verificationCode());
        }
        if (pending.linkedExternalId() != null && "SUCCEEDED".equals(cloud.outcome())
            && !pending.linkedExternalId().equals(cloud.externalDeviceId()))
            throw ApiError.conflict("CUSTOMER_VENDOR_MISMATCH", "云端匹配结果与本单位已关联来源不一致，未写入本地关联");
        transactions.executeWithoutResult(status -> applyResume(pending.registration(), cloud, requestId));
        return rawDetail(id);
    }

    private Pending prepareResume(AuthService.Identity actor, String id, String audience, boolean customer) {
        Registration snapshot = load(id);
        if (customer) requireCustomerAccess(actor, snapshot); else requireAccess(actor, snapshot, true);
        guard.begin(actor, "device:write", snapshot.tenantId(), audience);
        Registration registration = loadForUpdate(id);
        if (snapshot.tenantId() != null && !snapshot.tenantId().equals(registration.tenantId()))
            throw ApiError.conflict("REGISTRATION_CHANGED", "登记归属已变化，请刷新");
        if (customer && "NEW_VENDOR".equals(registration.mode())) requireCustomerNewVendor(actor, registration.tenantId());
        if ("PRODUCT_MODEL".equals(registration.mode()))
            throw ApiError.conflict("REGISTRATION_CLOUD_NOT_REQUIRED", "产品模型登记没有云端步骤，请直接完成本地验收");
        if (!List.of("DRAFT", "CLOUD_REGISTERED", "FAILED", "UNKNOWN").contains(registration.status()))
            throw ApiError.conflict("REGISTRATION_NOT_RESUMABLE", "当前登记状态不能继续云端步骤");
        if ("SUCCEEDED".equals(registration.cloudState()))
            throw ApiError.conflict("REGISTRATION_CLOUD_ALREADY_SUCCEEDED", "云端步骤已完成；请完成本地验收，不能重复新增");
        if ("IN_FLIGHT".equals(registration.cloudState()))
            throw ApiError.conflict("REGISTRATION_RECONCILE_REQUIRED", "上次提交结果未知；请先按序列号核对，不能直接重复提交");
        if ("UNKNOWN".equals(registration.cloudState()) && !"NOT_FOUND".equals(lastLookup(registration.id())))
            throw ApiError.conflict("REGISTRATION_RECONCILE_REQUIRED", "上次结果未知；只有核对确认云端不存在后才能显式重试");
        IntegrationDriver driver = drivers.require(registration.driverKey());
        if (!(driver instanceof VendorLifecycleDriver lifecycle))
            throw ApiError.conflict("DRIVER_LIFECYCLE_UNSUPPORTED", "该驱动没有可用的云端登记操作");
        boolean importedLedger = lifecycle.supportsImportedLedgerAssociation()
            && "MATCH_EXISTING".equals(registration.mode());
        if (!driver.configuration().configured())
            throw new ApiError(503, driver.configuration().code(), "接入驱动未启用或凭证不可用，未发起外部请求");
        String current = driver.accountFingerprint();
        if (registration.accountFingerprint() != null && current != null && !registration.accountFingerprint().equals(current))
            throw ApiError.conflict("ACCOUNT_CHANGED", "接入账号已轮换，不能用新账号续做旧登记；请新建登记");
        String linkedExternalId = null;
        if (customer && "MATCH_EXISTING".equals(registration.mode()) && !importedLedger) {
            LinkedSource linked = customerLinkedSource(registration.tenantId(), registration.externalSerial());
            if (registration.externalDeviceId() != null && !registration.externalDeviceId().equals(linked.externalId()))
                throw ApiError.conflict("CUSTOMER_VENDOR_MISMATCH", "登记来源与本单位已关联来源不一致");
            linkedExternalId = linked.externalId();
        } else if (importedLedger) {
            if (registration.externalDeviceId() == null || registration.externalDeviceId().isBlank())
                throw ApiError.conflict("LEGACY_LEDGER_ID_REQUIRED", "关联已导入旧台账资产必须提供旧平台台账ID");
            linkedExternalId = registration.externalDeviceId();
        }
        String verification = secrets.revealVerification(registration.id(), registration.verificationCipher(), registration.verificationNonce());
        OffsetDateTime now = OffsetDateTime.now(clock);
        int changed = jdbc.update("UPDATE device_registrations SET status='CLOUD_PENDING',cloud_state='IN_FLIGHT',external_device_id=COALESCE(external_device_id,?),account_fingerprint=COALESCE(account_fingerprint,?),revision=revision+1,updated_at=? WHERE id=? AND revision=?",
            linkedExternalId, current, now, id, registration.revision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "登记已更新，请刷新");
        event(id, "MATCH_EXISTING".equals(registration.mode()) ? "CLOUD_LOOKUP" : "CLOUD_SUBMIT", "IN_FLIGHT",
            importedLedger ? json.createObjectNode().put("mode", "IMPORTED_LOCAL").toString() : null, actor.user().id());
        return new Pending(load(id), lifecycle, verification, current, linkedExternalId, importedLedger);
    }

    private void applyResume(Registration registration, VendorLifecycleDriver.CloudResult cloud, String requestId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String status;
        String cloudState;
        String externalId = registration.externalDeviceId();
        String localState = registration.localState();
        switch (cloud.outcome()) {
            case "SUCCEEDED" -> {
                cloudState = "SUCCEEDED";
                status = "CLOUD_REGISTERED";
                externalId = cloud.externalDeviceId();
                boolean importedReady = "MATCH_EXISTING".equals(registration.mode()) && cloud.result() != null
                    && cloud.result().path("remote_lookup").asBoolean(false)
                    && cloud.result().path("identity_verified").asBoolean(false);
                localState = importedReady || (registration.tenantId() != null && registration.placeId() != null
                    && registration.productVersionId() != null) ? "READY" : "NOT_READY";
            }
            case "REJECTED", "FAILED" -> { cloudState = "FAILED"; status = "FAILED"; }
            case "BLOCKED" -> { cloudState = "NOT_ATTEMPTED"; status = "DRAFT"; }
            default -> { cloudState = "UNKNOWN"; status = "UNKNOWN"; }
        }
        int changed = jdbc.update("UPDATE device_registrations SET status=?,cloud_state=?,external_device_id=?,local_state=?,cloud_result_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
            status, cloudState, externalId, localState, cloudJson(cloud), now, registration.id(), registration.revision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "登记已更新，云端结果未写入；请刷新后核对");
        event(registration.id(), "CLOUD_RESULT", cloud.outcome(), cloudJson(cloud), registration.createdBy());
        audit.recordReason(registration.createdBy(), registration.tenantId(), "DEVICE_REGISTRATION_CLOUD",
            registration.id(), cloud.outcome(), requestId, cloud.note());
    }

    // ---------------- reconcile unknown cloud result ----------------

    public DeviceServiceModels.RegistrationDetail reconcile(AuthService.Identity actor, String id,
                                                           String requestId) {
        return doReconcile(actor, id, requestId, "management", false);
    }

    public DeviceServiceModels.CustomerRegistrationDetail reconcileForCustomer(AuthService.Identity actor, String id,
                                                                              String requestId) {
        Registration registration = load(id);
        requireCustomerAccess(actor, registration);
        if ("NEW_VENDOR".equals(registration.mode())) requireCustomerNewVendor(actor, registration.tenantId());
        doReconcile(actor, id, requestId, "customer", true);
        return customerDetail(id);
    }

    private DeviceServiceModels.RegistrationDetail doReconcile(AuthService.Identity actor, String id,
                                                               String requestId,
                                                               String audience, boolean customer) {
        Pending pending = transactions.execute(status -> prepareReconcile(actor, id, audience, customer));
        if (pending == null) throw new IllegalStateException("Registration reconcile preparation failed");
        VendorLifecycleDriver.LookupResult lookup;
        if (pending.importedLedger()) {
            lookup = importedLookup(pending);
        } else {
            lookup = pending.driver().lookup(pending.registration().externalSerial(),
                pending.registration().externalDeviceId());
        }
        transactions.executeWithoutResult(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            ObjectNode detail = json.createObjectNode();
            detail.put("lookup_state", lookup.state());
            if (lookup.result() != null) detail.set("lookup_result", lookup.result());
            if (lookup.note() != null) detail.put("note", lookup.note());
            boolean customerNewVendor = customer && "NEW_VENDOR".equals(pending.registration().mode());
            if (customerNewVendor) {
                // A customer cannot claim ownership by serial existence after an unknown add; platform resolution is required.
                if (jdbc.update("UPDATE device_registrations SET status=CASE WHEN cloud_state='IN_FLIGHT' THEN 'UNKNOWN' ELSE status END,cloud_state=CASE WHEN cloud_state='IN_FLIGHT' THEN 'UNKNOWN' ELSE cloud_state END,cloud_result_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                        detail.toString(), now, id, pending.registration().revision()) != 1)
                    throw ApiError.conflict("REVISION_CONFLICT", "登记已更新，核对结果未写入");
                String outcome = "FOUND".equals(lookup.state()) ? "PLATFORM_RESOLUTION_REQUIRED" : lookup.state();
                event(id, "CLOUD_LOOKUP", outcome, detail.toString(), actor.user().id());
            } else if ("FOUND".equals(lookup.state())) {
                String localStateExpression = pending.importedLedger() ? "'READY'"
                    : "CASE WHEN tenant_id IS NOT NULL AND place_id IS NOT NULL AND product_version_id IS NOT NULL THEN 'READY' ELSE 'NOT_READY' END";
                if (jdbc.update("UPDATE device_registrations SET status='CLOUD_REGISTERED',cloud_state='SUCCEEDED',external_device_id=?,local_state=" + localStateExpression + ",cloud_result_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                        lookup.externalDeviceId(), detail.toString(), now, id, pending.registration().revision()) != 1)
                    throw ApiError.conflict("REVISION_CONFLICT", "登记已更新，核对结果未写入");
                event(id, "CLOUD_LOOKUP", lookup.state(), detail.toString(), actor.user().id());
            } else {
                if (jdbc.update("UPDATE device_registrations SET status=CASE WHEN cloud_state='IN_FLIGHT' THEN 'UNKNOWN' ELSE status END,cloud_state=CASE WHEN cloud_state='IN_FLIGHT' THEN 'UNKNOWN' ELSE cloud_state END,cloud_result_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                        detail.toString(), now, id, pending.registration().revision()) != 1)
                    throw ApiError.conflict("REVISION_CONFLICT", "登记已更新，核对结果未写入");
                event(id, "CLOUD_LOOKUP", lookup.state(), detail.toString(), actor.user().id());
            }
            audit.recordReason(actor.user().id(), pending.registration().tenantId(), "DEVICE_REGISTRATION_RECONCILE",
                id, lookup.state(), requestId, lookup.note());
        });
        return rawDetail(id);
    }

    /**
     * Imported-asset matching is never local-only when an exact ledger id exists. The driver first
     * proves the remote dId/dEui with the currently configured account, then the local mirror must
     * name the same exact ledger id. Any uncertainty stays UNKNOWN and no binding is written.
     */
    private VendorLifecycleDriver.LookupResult importedLookup(Pending pending) {
        Registration registration = pending.registration();
        if (registration.externalDeviceId() == null || registration.externalDeviceId().isBlank())
            return new VendorLifecycleDriver.LookupResult("UNKNOWN", null, null,
                "关联已导入旧台账资产必须提供精确台账ID(dId)；未调用远端查询");
        VendorLifecycleDriver.LookupResult remote = pending.driver().lookup(registration.externalSerial(),
            registration.externalDeviceId());
        String currentAccount = drivers.require(registration.driverKey()).accountFingerprint();
        ObjectNode evidence = json.createObjectNode();
        evidence.put("remote_state", remote.state());
        evidence.put("ledger_id", registration.externalDeviceId());
        if (registration.accountFingerprint() != null && currentAccount != null
            && !registration.accountFingerprint().equals(currentAccount)) {
            evidence.put("account_changed", true);
            return new VendorLifecycleDriver.LookupResult("UNKNOWN", registration.externalDeviceId(), evidence,
                "接入账号在核对期间发生变化；结果不能用于关联，请刷新后重新核对");
        }
        if (remote.note() != null) evidence.put("remote_note", remote.note());
        if (remote.result() != null) evidence.set("remote", remote.result());
        if (!"FOUND".equals(remote.state())) {
            evidence.put("remote_lookup", remote.result() != null);
            evidence.put("identity_verified", false);
            return new VendorLifecycleDriver.LookupResult(remote.state(), remote.externalDeviceId(), evidence,
                "旧平台精确详情核对未通过：" + remote.note());
        }
        ImportedLegacyAssetMatcher.Match match = importedAssets.match(registration.tenantId(),
            registration.driverKey(), registration.externalSerial(), registration.externalDeviceId());
        evidence.put("local_match_state", match.state());
        evidence.put("remote_lookup", true);
        evidence.put("identity_verified", true);
        if (match.deviceId() != null) evidence.put("local_device_id", match.deviceId());
        if (match.externalDeviceId() != null) evidence.put("local_ledger_id", match.externalDeviceId());
        evidence.put("local_note", match.note());
        if (!"FOUND".equals(match.state()))
            return new VendorLifecycleDriver.LookupResult(match.state(), registration.externalDeviceId(), evidence,
                "旧平台详情已通过精确EUI/台账ID核对，但本平台导入台账未匹配：" + match.note());
        if (remote.externalDeviceId() != null && !remote.externalDeviceId().equals(match.externalDeviceId()))
            return new VendorLifecycleDriver.LookupResult("AMBIGUOUS", registration.externalDeviceId(), evidence,
                "旧平台与本地导入台账的台账ID不一致；未建立关联");
        return new VendorLifecycleDriver.LookupResult("FOUND", match.externalDeviceId(), evidence,
            "旧平台详情已核对EUI与台账ID，且本平台导入台账精确匹配；远端查询使用当前配置账号指纹");
    }

    private Pending prepareReconcile(AuthService.Identity actor, String id, String audience, boolean customer) {
        Registration snapshot = load(id);
        if (customer) requireCustomerAccess(actor, snapshot); else requireAccess(actor, snapshot, true);
        guard.begin(actor, "device:write", snapshot.tenantId(), audience);
        Registration registration = loadForUpdate(id);
        if (snapshot.tenantId() != null && !snapshot.tenantId().equals(registration.tenantId()))
            throw ApiError.conflict("REGISTRATION_CHANGED", "登记归属已变化，请刷新");
        if ("PRODUCT_MODEL".equals(registration.mode()))
            throw ApiError.conflict("REGISTRATION_CLOUD_NOT_REQUIRED", "产品模型登记没有云端步骤");
        if ("IN_FLIGHT".equals(registration.cloudState()) && registration.updatedAt() != null
            && registration.updatedAt().plusSeconds(300).isAfter(OffsetDateTime.now(clock)))
            throw ApiError.conflict("REGISTRATION_IN_FLIGHT", "云端步骤正在提交，请稍后核对");
        if (!List.of("CLOUD_PENDING", "UNKNOWN", "CLOUD_REGISTERED").contains(registration.status())
            && !"IN_FLIGHT".equals(registration.cloudState()))
            throw ApiError.conflict("REGISTRATION_NOT_RECONCILABLE", "当前登记状态不需要云端核对");
        IntegrationDriver driver = drivers.require(registration.driverKey());
        if (!(driver instanceof VendorLifecycleDriver lifecycle))
            throw ApiError.conflict("DRIVER_LIFECYCLE_UNSUPPORTED", "该驱动没有可用的云端核对操作");
        if (!driver.configuration().configured())
            throw new ApiError(503, driver.configuration().code(), "接入驱动未启用或凭证不可用，未发起外部请求");
        boolean importedLedger = lifecycle.supportsImportedLedgerAssociation()
            && "MATCH_EXISTING".equals(registration.mode());
        return new Pending(registration, lifecycle, null, driver.accountFingerprint(), null, importedLedger);
    }

    // ---------------- commission ----------------

    @Transactional
    public DeviceServiceModels.RegistrationDetail commission(AuthService.Identity actor, String id, String key,
                                                             DeviceServiceInputs.CommissionRegistration input,
                                                             String requestId) {
        return doCommission(actor, id, key, input, requestId, "management", false);
    }

    @Transactional
    public DeviceServiceModels.CustomerRegistrationDetail commissionForCustomer(AuthService.Identity actor, String id, String key,
                                                                              DeviceServiceInputs.CommissionRegistration input,
                                                                              String requestId) {
        String tenant = actor.user().tenantId();
        if (tenant == null || tenant.isBlank() || !tenant.equals(input.tenantId()))
            throw ApiError.forbidden("客户只能验收本单位登记");
        Registration registration = load(id);
        if ("NEW_VENDOR".equals(registration.mode())) requireCustomerNewVendor(actor, tenant);
        doCommission(actor, id, key, input, requestId, "customer", true);
        return customerDetail(id);
    }

    private DeviceServiceModels.RegistrationDetail doCommission(AuthService.Identity actor, String id, String key,
                                                                DeviceServiceInputs.CommissionRegistration input,
                                                                String requestId, String audience, boolean customer) {
        Registration snapshot = load(id);
        if (customer) requireCustomerAccess(actor, snapshot); else requireAccess(actor, snapshot, true);
        coordination.lock();
        guard.begin(actor, "device:write", input.tenantId(), audience);
        idem.validateKey(key);
        Registration registration = loadForUpdate(id);
        if (registration.tenantId() != null && !registration.tenantId().equals(input.tenantId()))
            throw ApiError.conflict("REGISTRATION_TENANT_CHANGED", "登记单位已确定，不能用其他单位验收");
        if (customer && "PRODUCT_MODEL".equals(registration.mode()))
            throw ApiError.conflict("CUSTOMER_REGISTRATION_MODE", "客户不能使用产品模型登记");
        if ("COMMISSIONED".equals(registration.status())) return rawDetail(id);
        if (!"DRAFT".equals(registration.status()) && !"CLOUD_REGISTERED".equals(registration.status()))
            throw ApiError.conflict("REGISTRATION_NOT_COMMISSIONABLE", "登记尚未完成云端步骤或已失败");
        if ("VENDOR_PLATFORM".equals(drivers.require(registration.driverKey()).mode())
            && !"SUCCEEDED".equals(registration.cloudState()))
            throw ApiError.conflict("REGISTRATION_CLOUD_REQUIRED", "必须先完成并核对云端步骤，才能本地验收");
        String placeName = place(input.tenantId(), input.placeId());
        IntegrationDriver commissionDriver = drivers.require(registration.driverKey());
        if ("VENDOR_PLATFORM".equals(commissionDriver.mode())) {
            String currentAccount = commissionDriver.accountFingerprint();
            if (registration.accountFingerprint() != null && currentAccount != null
                && !registration.accountFingerprint().equals(currentAccount))
                throw ApiError.conflict("ACCOUNT_CHANGED", "接入账号已轮换，不能用新账号验收旧登记；请重新核对后新建");
        }
        boolean importedAssociation = commissionDriver instanceof VendorLifecycleDriver lifecycle
            && lifecycle.supportsImportedLedgerAssociation() && "MATCH_EXISTING".equals(registration.mode());
        boolean nativeHikCreate = "NEW_VENDOR".equals(registration.mode())
            && "HIKVISION_FIRECLOUD".equals(registration.driverKey());
        PublishedVersionCatalog.PublishedVersion version = null;
        if (!importedAssociation && (!nativeHikCreate || (input.productVersionId() != null
                && !input.productVersionId().isBlank()))) {
            if (input.productVersionId() == null || input.productVersionId().isBlank())
                throw ApiError.bad("本地验收必须选择已发布产品版本");
            version = catalog.requireAvailableForNewUse(input.productVersionId());
        }
        String deviceId;
        String versionId;
        if (importedAssociation) {
            Association association = associateImported(actor, registration, input);
            deviceId = association.deviceId();
            versionId = association.productVersionId();
            if (input.productVersionId() != null && !input.productVersionId().isBlank()
                && !input.productVersionId().equals(versionId))
                throw ApiError.conflict("IMPORTED_VERSION_IMMUTABLE",
                    "已导入旧台账资产保留导入时产品身份；没有固定版本的导入资产不能在验收时伪造产品版本");
        } else if ("MATCH_EXISTING".equals(registration.mode())) {
            deviceId = linkExisting(actor, registration, input, customer);
            versionId = version.id();
        } else {
            scopes.requirePlace(actor.user().id(), input.tenantId(), input.placeId());
            deviceId = createLocal(actor, registration, input, version, placeName);
            versionId = version == null ? null : version.id();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        int changed = jdbc.update("UPDATE device_registrations SET status='COMMISSIONED',tenant_id=?,place_id=?,product_version_id=?,device_id=?,local_state='SUCCEEDED',acceptance_note=?,revision=revision+1,updated_at=?,commissioned_at=? WHERE id=? AND revision=?",
            input.tenantId(), input.placeId(), versionId, deviceId, input.acceptanceNote().trim(), now, now, id,
            registration.revision());
        if (changed != 1) throw ApiError.conflict("REVISION_CONFLICT", "登记已更新，请刷新");
        event(id, "LOCAL_COMMISSION", "SUCCEEDED", json.createObjectNode().put("device_id", deviceId).put("product_version_id", versionId).toString(), actor.user().id());
        audit.recordReason(actor.user().id(), input.tenantId(), "DEVICE_REGISTRATION_COMMISSION", id, "SUCCESS", requestId, input.acceptanceNote());
        return rawDetail(id);
    }

    private record Association(String deviceId, String productVersionId) { }

    private Association associateImported(AuthService.Identity actor, Registration registration,
                                          DeviceServiceInputs.CommissionRegistration input) {
        String deviceId = input.existingDeviceId();
        if (deviceId == null || deviceId.isBlank())
            throw ApiError.bad("关联已导入旧台账资产必须显式选择已有本地设备，不能自动取第一行");
        scopes.requireDevice(actor.user().id(), input.tenantId(), deviceId);
        var rows = jdbc.query("SELECT revision,archived_at,is_simulated,product_version_id,device_eui FROM devices WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getBoolean(3),
                rs.getString(4), rs.getString(5)}, deviceId, input.tenantId());
        if (rows.isEmpty()) throw ApiError.missing();
        Object[] device = rows.getFirst();
        if (device[1] != null) throw ApiError.conflict("DEVICE_ARCHIVED", "已归档设备不能再关联来源");
        if (Boolean.TRUE.equals(device[2])) throw ApiError.conflict("SIMULATED_DEVICE_FORBIDDEN", "历史模拟设备不能关联真实来源");
        if (input.existingDeviceRevision() != null && ((Number) device[0]).longValue() != input.existingDeviceRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "本地设备已更新，请刷新后重新选择");
        requireImportedRemoteVerification(registration);
        String productVersionId = (String) device[3];
        if (productVersionId != null && productVersionId.isBlank()) productVersionId = null;
        String serial = registration.externalSerial();
        String external = registration.externalDeviceId();
        ImportedLegacyAssetMatcher.Match match = importedAssets.match(input.tenantId(), registration.driverKey(),
            serial, external);
        if (!"FOUND".equals(match.state()) || !deviceId.equals(match.deviceId()))
            throw ApiError.conflict("IMPORTED_ASSET_MISMATCH", match.note());
        if (bindings.activeForDevice(input.tenantId(), deviceId).isPresent())
            throw ApiError.conflict("DEVICE_SOURCE_BOUND", "设备已有生效来源关联，请先显式解绑");
        if (bindings.activeBySource(registration.driverKey(), external).isPresent())
            throw ApiError.conflict("SOURCE_ALREADY_BOUND", "该旧台账资源已关联其他本地设备");
        bindings.bind(input.tenantId(), deviceId, registration.driverKey(), external, serial,
            registration.accountFingerprint(), actor.user().id());
        return new Association(deviceId, productVersionId);
    }

    /**
     * Binding is a local write, so the remote identity proof must already exist from resume/reconcile.
     * A legacy row created by an older build (local-only match) must be reconciled before binding.
     */
    private void requireImportedRemoteVerification(Registration registration) {
        JsonNode cloud = parseNullable(registration.cloudResultJson());
        JsonNode detail = cloud == null ? null : cloud.path("result");
        if (detail == null || detail.isMissingNode() || detail.isNull())
            detail = cloud == null ? null : cloud.path("lookup_result");
        boolean verified = detail != null && detail.path("remote_lookup").asBoolean(false)
            && detail.path("identity_verified").asBoolean(false);
        if (!verified)
            throw ApiError.conflict("LEGACY_REMOTE_UNVERIFIED",
                "该导入资产尚未由当前配置账号精确核对远端EUI与台账ID；请先核对再关联，不能仅凭本地台账假设");
        String ledger = detail.path("ledger_id").asText(null);
        if (ledger != null && !ledger.equals(registration.externalDeviceId()))
            throw ApiError.conflict("LEGACY_REMOTE_UNVERIFIED", "远端核对台账ID与登记不一致；请重新核对");
    }

    private String createLocal(AuthService.Identity actor, Registration registration,
                               DeviceServiceInputs.CommissionRegistration input,
                               PublishedVersionCatalog.PublishedVersion version, String placeName) {
        String eui = registration.externalSerial();
        if ("NEW_VENDOR".equals(registration.mode()) && version != null && registration.deviceModel() != null
            && !registration.deviceModel().equals(version.model()))
            throw ApiError.conflict("PRODUCT_MODEL_MISMATCH", "所选产品版本型号与厂商型号不一致；请选择匹配的具体版本，不能借用其他型号能力");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'", Long.class, eui) != 0)
            throw ApiError.conflict("DEVICE_EUI_CONFLICT", "设备标识已被使用");
        String name = input.deviceName() == null || input.deviceName().isBlank()
            ? (registration.deviceName() == null || registration.deviceName().isBlank()
                ? (version == null ? registration.deviceModel() : version.productName()) : registration.deviceName())
            : input.deviceName().trim();
        String address = input.address() == null ? (registration.location() == null ? "" : registration.location()) : input.address().trim();
        String model = version == null ? registration.deviceModel() : version.model();
        String productId = version == null ? null : version.productId();
        String productVersionId = version == null ? null : version.id();
        String productName = version == null ? null : version.productName();
        Long versionNumber = version == null ? null : version.versionNumber();
        String deviceId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,product_id,product_version_id,product_name,version_number,revision,integration_state,system_type,device_category,ownership_from) VALUES(?,?,?,?,?,?,?,'UNKNOWN',FALSE,?,?,?,?,0,'PENDING_INTEGRATION',?,?,?)",
            deviceId, input.tenantId(), name, eui, model, address, input.placeId(), productId,
            productVersionId, productName, versionNumber, input.systemType(), input.deviceCategory(), now);
        jdbc.update("INSERT INTO device_place_history(id,device_id,tenant_id,action,to_place_id,to_place_name,revision_after,actor_id,occurred_at) VALUES(?,?,?,'REGISTER',?,?,0,?,?)",
            UUID.randomUUID().toString(), deviceId, input.tenantId(), input.placeId(), placeName, actor.user().id(), now);
        if (version != null)
            jdbc.update("INSERT INTO device_version_history(id,device_id,tenant_id,from_version_id,to_version_id,reason,actor_id,occurred_at) VALUES(?,?,?,NULL,?,'COMMISSION',?,?)",
                UUID.randomUUID().toString(), deviceId, input.tenantId(), version.id(), actor.user().id(), now);
        if ("NEW_VENDOR".equals(registration.mode())) {
            IntegrationDriver driver = drivers.require(registration.driverKey());
            if (driver instanceof VendorLifecycleDriver lifecycle && lifecycle.supportsImportedLedgerAssociation())
                linkLegacyVendorSource(actor, registration, deviceId, input.tenantId());
            else
                linkVendorSource(actor, registration, deviceId, now, input.tenantId());
        }
        return deviceId;
    }

    /**
     * A newly added TTL/TSF ledger device is bound directly under its own driver. The Hikvision
     * discovery table is not a legacy ledger store and must not receive a fabricated Hik record.
     */
    private void linkLegacyVendorSource(AuthService.Identity actor, Registration registration, String deviceId,
                                        String tenantId) {
        String external = registration.externalDeviceId();
        if (external == null || external.isBlank())
            throw ApiError.conflict("REGISTRATION_EXTERNAL_ID_MISSING", "云端已接受但未保存台账标识，不能本地验收");
        String serial = registration.externalSerial();
        if (serial == null || serial.isBlank())
            throw ApiError.conflict("REGISTRATION_SERIAL_MISSING", "登记缺少设备编号，不能建立来源关联");
        bindings.bind(tenantId, deviceId, registration.driverKey(), external, serial,
            registration.accountFingerprint(), actor.user().id());
    }

    private void linkVendorSource(AuthService.Identity actor, Registration registration, String deviceId, OffsetDateTime ownershipFrom, String tenantId) {
        String external = registration.externalDeviceId();
        if (external == null || external.isBlank())
            throw ApiError.conflict("REGISTRATION_EXTERNAL_ID_MISSING", "云端已接受但未保存设备标识，不能本地验收");
        var rows = jdbc.query("SELECT local_device_id,serial FROM hikvision_discovered_devices WHERE external_id=? FOR UPDATE",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, external);
        String serial = registration.externalSerial();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime historyFrom = now.isAfter(ownershipFrom) ? now : ownershipFrom;
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO hikvision_discovered_devices(external_id,resource_kind,serial,name,model,device_type,metadata_json,local_device_id,event_history_from,first_seen_at,last_seen_at) VALUES(?,'DEVICE',?,?,?,?,?,?,?,?,?)",
                external, serial, registration.deviceName() == null ? external : registration.deviceName(),
                registration.deviceModel(), registration.deviceType() == null ? null : String.valueOf(registration.deviceType()),
                sanitizedCloud(registration), deviceId, historyFrom, now, now);
        } else {
            String[] row = rows.getFirst();
            if (row[1] != null && serial != null && !row[1].equals(serial))
                throw ApiError.conflict("SOURCE_SERIAL_MISMATCH", "云端资源序列号与登记不一致，不能自动关联");
            if (row[0] != null && !row[0].equals(deviceId))
                throw ApiError.conflict("SOURCE_ALREADY_LINKED", "云端资源已关联其他本地设备");
            // Facts observed before local ownership must not be applied to the fresh asset.
            jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,event_history_from=CASE WHEN event_history_from IS NULL OR event_history_from<? THEN ? ELSE event_history_from END,revision=revision+1 WHERE external_id=?",
                deviceId, historyFrom, historyFrom, external);
        }
        installBinding(actor, registration, tenantId, deviceId, external, rowSerial(rows, serial));
        events.publishEvent(new DeviceRegistrationSourceLinked(registration.driverKey(), external, now));
    }

    private String rowSerial(List<String[]> rows, String fallback) {
        return rows.isEmpty() || rows.getFirst()[1] == null ? fallback : rows.getFirst()[1];
    }

    private String linkExisting(AuthService.Identity actor, Registration registration,
                                DeviceServiceInputs.CommissionRegistration input, boolean customer) {
        String deviceId = input.existingDeviceId();
        if (deviceId == null || deviceId.isBlank())
            throw ApiError.bad("匹配已有云端资源必须显式选择已有本地设备，不能自动取第一行");
        scopes.requireDevice(actor.user().id(), input.tenantId(), deviceId);
        var rows = jdbc.query("SELECT revision,archived_at,is_simulated,ownership_from FROM devices WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> new Object[]{rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getBoolean(3),
                rs.getObject(4, OffsetDateTime.class)}, deviceId, input.tenantId());
        if (rows.isEmpty()) throw ApiError.missing();
        Object[] device = rows.getFirst();
        if (device[1] != null) throw ApiError.conflict("DEVICE_ARCHIVED", "已归档设备不能再关联来源");
        if (Boolean.TRUE.equals(device[2])) throw ApiError.conflict("SIMULATED_DEVICE_FORBIDDEN", "历史模拟设备不能关联真实来源");
        if (input.existingDeviceRevision() != null && ((Number) device[0]).longValue() != input.existingDeviceRevision())
            throw ApiError.conflict("REVISION_CONFLICT", "本地设备已更新，请刷新后重新选择");
        OffsetDateTime ownershipFrom = (OffsetDateTime) device[3];
        String external = registration.externalDeviceId();
        var discoveredRows = jdbc.query("SELECT external_id,local_device_id,serial FROM hikvision_discovered_devices WHERE external_id=? FOR UPDATE",
            (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, external);
        if (discoveredRows.isEmpty()) {
            var bySerial = jdbc.query("SELECT external_id,local_device_id,serial FROM hikvision_discovered_devices WHERE serial=? AND resource_kind='DEVICE' FOR UPDATE",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, registration.externalSerial());
            if (bySerial.size() != 1)
                throw ApiError.conflict("SOURCE_NOT_DISCOVERED", "未按精确序列号找到唯一云端资源；请先同步或核对登记");
            discoveredRows = bySerial;
        }
        String[] discovered = discoveredRows.getFirst();
        if (customer) {
            boolean allocationProof = discovered[1] == null && "SUCCEEDED".equals(registration.cloudState())
                && registration.tenantId() != null;
            if (discovered[1] == null && !allocationProof)
                throw ApiError.conflict("CUSTOMER_VENDOR_NOT_ASSIGNED", "客户只能匹配本单位已关联或平台已确认分配的云端资源");
            if (discovered[1] != null && !discovered[1].equals(deviceId))
                throw ApiError.conflict("CUSTOMER_VENDOR_MISMATCH", "所选本地设备与云端资源已关联设备不一致");
            Long sameUnit = jdbc.queryForObject("SELECT COUNT(*) FROM devices d WHERE d.id=? AND d.tenant_id=? AND d.archived_at IS NULL",
                Long.class, deviceId, registration.tenantId());
            if (sameUnit == null || sameUnit != 1)
                throw ApiError.missing();
        }
        if (discovered[1] != null && !discovered[1].equals(deviceId))
            throw ApiError.conflict("SOURCE_ALREADY_LINKED", "云端资源已关联其他本地设备");
        Long other = jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE local_device_id=?", Long.class, deviceId);
        if (other != null && other != 0) throw ApiError.conflict("DEVICE_SOURCE_BOUND", "本地设备已关联其他云端资源，请先显式解绑");
        if (ownershipFrom == null) {
            jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,revision=revision+1 WHERE external_id=?", deviceId, discovered[0]);
        } else {
            jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,event_history_from=CASE WHEN event_history_from IS NULL OR event_history_from<? THEN ? ELSE event_history_from END,revision=revision+1 WHERE external_id=?",
                deviceId, ownershipFrom, ownershipFrom, discovered[0]);
        }
        installBinding(actor, registration, input.tenantId(), deviceId, discovered[0], discovered[2] == null ? registration.externalSerial() : discovered[2]);
        return deviceId;
    }

    private void installBinding(AuthService.Identity actor, Registration registration, String tenantId, String deviceId,
                                String external, String serial) {
        bindings.bind(tenantId, deviceId, registration.driverKey(), external, serial,
            registration.accountFingerprint(), actor.user().id());
    }

    // ---------------- abandon ----------------

    @Transactional
    public DeviceServiceModels.RegistrationDetail abandon(AuthService.Identity actor, String id, String key,
                                                          DeviceServiceInputs.AbandonRegistration input, String requestId) {
        return doAbandon(actor, id, key, input, requestId, "management", false);
    }

    @Transactional
    public DeviceServiceModels.CustomerRegistrationDetail abandonForCustomer(AuthService.Identity actor, String id, String key,
                                                                             DeviceServiceInputs.AbandonRegistration input,
                                                                             String requestId) {
        doAbandon(actor, id, key, input, requestId, "customer", true);
        return customerDetail(id);
    }

    private DeviceServiceModels.RegistrationDetail doAbandon(AuthService.Identity actor, String id, String key,
                                                             DeviceServiceInputs.AbandonRegistration input,
                                                             String requestId, String audience, boolean customer) {
        Registration snapshot = load(id);
        if (customer) requireCustomerAccess(actor, snapshot); else requireAccess(actor, snapshot, true);
        guard.begin(actor, "device:write", snapshot.tenantId(), audience);
        idem.validateKey(key);
        Registration registration = loadForUpdate(id);
        if (registration.tenantId() != null) guard.requireCurrent(actor, "device:write", registration.tenantId(), audience);
        if ("COMMISSIONED".equals(registration.status()))
            throw ApiError.conflict("REGISTRATION_COMMISSIONED", "已验收登记不能放弃；请通过解绑/退役流程处理");
        IntegrationDriver driver = drivers.require(registration.driverKey());
        boolean importedAssociation = driver instanceof VendorLifecycleDriver lifecycle
            && lifecycle.supportsImportedLedgerAssociation() && "MATCH_EXISTING".equals(registration.mode());
        if ("SUCCEEDED".equals(registration.cloudState()) && !importedAssociation)
            throw ApiError.conflict("REGISTRATION_CLOUD_RECORD_EXISTS", "云端记录已存在；请先显式执行云端删除并核对，不能直接放弃");
        if ("ABANDONED".equals(registration.status())) return rawDetail(id);
        jdbc.update("UPDATE device_registrations SET status='ABANDONED',revision=revision+1,updated_at=? WHERE id=? AND status<>'ABANDONED'",
            OffsetDateTime.now(clock), id);
        event(id, "ABANDON", "ABANDONED", json.createObjectNode().put("reason", input.reason().trim()).toString(), actor.user().id());
        audit.recordReason(actor.user().id(), registration.tenantId(), "DEVICE_REGISTRATION_ABANDON", id, "SUCCESS", requestId, input.reason());
        return rawDetail(id);
    }

    // ---------------- helpers ----------------

    private String place(String tenant, String placeId) {
        var names = jdbc.query("SELECT name FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",
            (rs, n) -> rs.getString(1), placeId, tenant);
        if (names.isEmpty()) throw ApiError.bad("位置不存在、不属于当前单位或已归档");
        return names.getFirst();
    }

    /** Unassigned pool is creator-only unless the platform global key applies; assigned rows always need unit/device scope. */
    private void requireAccess(AuthService.Identity actor, Registration registration, boolean write) {
        String actorId = actor.user().id();
        if (registration.tenantId() == null) {
            if (registration.createdBy() != null && registration.createdBy().equals(actorId)) return;
            if (guard.hasCurrentPermission(actor, write ? WRITE_GLOBAL : READ_GLOBAL)) return;
            if (!write && guard.hasCurrentPermission(actor, WRITE_GLOBAL)) return;
            throw ApiError.missing();
        }
        if (!granted(actorId, registration.tenantId())) throw ApiError.missing();
        if (registration.deviceId() != null) {
            if (!scopes.contains(actorId, registration.tenantId(), registration.deviceId())) throw ApiError.missing();
            return;
        }
        if (registration.createdBy() != null && registration.createdBy().equals(actorId)) return;
        if (scopes.all(actorId, registration.tenantId())) return;
        throw ApiError.missing();
    }

    private void requireCustomerNewVendor(AuthService.Identity actor, String tenant) {
        if (tenant == null || !guard.hasCurrentPermission(actor, "device-registrations:write")
            || !scopes.all(actor.user().id(), tenant))
            throw ApiError.forbidden("当前客户缺少厂商新增登记所需的独立权限或全单位范围");
    }

    private void customerPreflightAbsent(AuthService.Identity actor, Registration registration) {
        IntegrationDriver driver = transactions.execute(status -> {
            guard.begin(actor, "device:write", registration.tenantId(), "customer");
            Registration current = load(registration.id());
            requireCustomerAccess(actor, current);
            IntegrationDriver resolved = drivers.require(current.driverKey());
            if (!(resolved instanceof VendorLifecycleDriver) || !resolved.configuration().configured())
                throw ApiError.conflict("CUSTOMER_PRECHECK_UNAVAILABLE", "无法确认精确序列号在云端不存在；未发起登记，请联系平台处理");
            return resolved;
        });
        if (driver instanceof VendorLifecycleDriver lifecycle && lifecycle.supportsImportedLedgerAssociation()) {
            throw ApiError.conflict("CUSTOMER_PRECHECK_UNAVAILABLE",
                "旧台账精确查询合同未验证；客户不能自行新增，请使用已导入资产关联或联系平台处理");
        }
        VendorLifecycleDriver.LookupResult lookup = ((VendorLifecycleDriver) driver).lookup(registration.externalSerial(), null);
        if (!"NOT_FOUND".equals(lookup.state()))
            throw ApiError.conflict("CUSTOMER_PRECHECK_UNAVAILABLE", "无法确认精确序列号在云端不存在；未发起登记，请联系平台处理");
    }

    private DeviceServiceModels.CustomerRegistrationDetail customerDetail(String id) { return customerDetail(load(id)); }
    private DeviceServiceModels.CustomerRegistrationDetail customerDetail(Registration registration) {
        List<DeviceServiceModels.CustomerRegistrationEvent> events = jdbc.query("SELECT step,outcome,occurred_at FROM device_registration_events WHERE registration_id=? ORDER BY occurred_at,id",
            (rs, n) -> new DeviceServiceModels.CustomerRegistrationEvent(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class)), registration.id());
        return new DeviceServiceModels.CustomerRegistrationDetail(customerSummary(registration), registration.deviceModel(),
            registration.location(), registration.acceptanceNote(), registration.vendorPayloadCipher() != null, events);
    }

    private static DeviceServiceModels.CustomerRegistrationSummary customerSummary(Registration registration) {
        return new DeviceServiceModels.CustomerRegistrationSummary(registration.id(), registration.driverKey(),
            registration.mode(), registration.status(), registration.revision(), registration.deviceId(),
            registration.externalSerial(), registration.deviceName(),
            registration.cloudState(), registration.localState(), registration.placeId(), registration.productVersionId(),
            registration.createdAt(), registration.updatedAt(), registration.commissionedAt());
    }

    private DeviceServiceModels.RegistrationDetail rawDetail(String id) { return detailView(load(id)); }

    private void requireCustomerAccess(AuthService.Identity actor, Registration registration) {
        String tenant = actor.user().tenantId();
        if (tenant == null || registration.tenantId() == null || !tenant.equals(registration.tenantId())) throw ApiError.missing();
        String actorId = actor.user().id();
        if (registration.deviceId() != null) {
            if (!scopes.contains(actorId, tenant, registration.deviceId())) throw ApiError.missing();
            return;
        }
        if (registration.createdBy() != null && registration.createdBy().equals(actorId)) return;
        if (scopes.all(actorId, tenant)) return;
        throw ApiError.missing();
    }

    private record LinkedSource(String externalId, String deviceId, String serial) { }
    private LinkedSource customerLinkedSource(String tenant, String serial) {
        var rows = jdbc.query("SELECT h.external_id,h.local_device_id,h.serial FROM hikvision_discovered_devices h JOIN devices d ON d.id=h.local_device_id AND d.tenant_id=? AND d.archived_at IS NULL WHERE h.serial=? AND h.resource_kind='DEVICE'",
            (rs, n) -> new LinkedSource(rs.getString(1), rs.getString(2), rs.getString(3)), tenant, serial);
        if (rows.size() != 1)
            throw ApiError.conflict("CUSTOMER_VENDOR_NOT_ASSIGNED", "客户只能匹配本单位已关联的精确来源资源；未关联或匹配不唯一时拒绝");
        return rows.getFirst();
    }

    private void customerImportedScope(AuthService.Identity actor, String tenant, String driverKey,
                                       String serial, String externalDeviceId) {
        ImportedLegacyAssetMatcher.Match match = importedAssets.match(tenant, driverKey, serial, externalDeviceId);
        if (!"FOUND".equals(match.state()))
            throw ApiError.conflict("CUSTOMER_IMPORTED_ASSET_NOT_FOUND", match.note());
        scopes.requireDevice(actor.user().id(), tenant, match.deviceId());
    }

    public record DriverOption(String key, String mode, String displayName, boolean configured, boolean enabled,
                               boolean legacyOriginAccount, boolean importedLedgerAssociation) { }
    public List<DriverOption> driverOptionsForCustomer() {
        List<DriverOption> options = new ArrayList<>();
        for (IntegrationDriver driver : drivers.all()) {
            var configuration = driver.configuration();
            boolean imported = driver instanceof VendorLifecycleDriver lifecycle
                && lifecycle.supportsImportedLedgerAssociation();
            options.add(new DriverOption(driver.key(), driver.mode(), driver.displayName(),
                configuration.configured(), configuration.enabled(), driver.configurableOriginAccount(), imported));
        }
        return List.copyOf(options);
    }

    private boolean granted(String actorId, String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?", Long.class, actorId, tenantId);
        return count != null && count != 0;
    }

    private String lastLookup(String id) {
        return jdbc.query("SELECT outcome FROM device_registration_events WHERE registration_id=? AND step='CLOUD_LOOKUP' ORDER BY occurred_at DESC,id DESC LIMIT 1",
            (rs, n) -> rs.getString(1), id).stream().findFirst().orElse(null);
    }

    private VendorLifecycleDriver.CloudRegistration vendorRegistration(Registration registration, String verificationCode) {
        JsonNode payload = registration.vendorPayloadCipher() == null ? null
            : secrets.revealVendorPayload(registration.id(), registration.vendorPayloadCipher());
        return new VendorLifecycleDriver.CloudRegistration(registration.deviceName(), registration.externalSerial(),
            registration.deviceModel(), registration.deviceType(), registration.communicationType(), verificationCode,
            registration.producterId(), registration.productModelId(), registration.location(),
            registration.autoAddChannel(), registration.vendorUnitId(), registration.latitude(), registration.longitude(),
            payload);
    }

    private VendorLifecycleDriver.CloudRegistration vendorRegistration(DeviceServiceInputs.CreateRegistration input) {
        return new VendorLifecycleDriver.CloudRegistration(input.deviceName(), input.externalSerial(),
            input.deviceModel(), input.deviceType(), input.communicationType(), input.verificationCode(),
            input.producterId(), input.productModelId(), input.location(), input.autoAddChannel(),
            null, input.latitude(), input.longitude(), input.vendorPayload());
    }

    private String cloudJson(VendorLifecycleDriver.CloudResult cloud) {
        ObjectNode node = json.createObjectNode();
        node.put("outcome", cloud.outcome());
        if (cloud.vendorCode() != null) node.put("vendor_code", cloud.vendorCode());
        if (cloud.note() != null) node.put("note", cloud.note());
        if (cloud.result() != null) node.set("result", cloud.result());
        return node.toString();
    }

    private String sanitizedCloud(Registration registration) {
        JsonNode result = parseNullable(registration.cloudResultJson());
        ObjectNode node = json.createObjectNode();
        node.put("source", "HIKVISION_FIRECLOUD_ADD");
        if (result != null) node.set("cloud", result);
        return node.toString();
    }

    private void event(String registrationId, String step, String outcome, String detail, String actorId) {
        jdbc.update("INSERT INTO device_registration_events(id,registration_id,step,outcome,detail_json,actor_id,occurred_at) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), registrationId, step, outcome, detail, actorId, OffsetDateTime.now(clock));
    }

    private Registration load(String id) {
        var rows = jdbc.query("SELECT * FROM device_registrations WHERE id=?", (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private Registration loadForUpdate(String id) {
        var rows = jdbc.query("SELECT * FROM device_registrations WHERE id=? FOR UPDATE", (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private JsonNode parseNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return json.readTree(raw); }
        catch (Exception invalid) { return null; }
    }

    private static Registration row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Registration(rs.getString("id"), rs.getString("driver_key"), rs.getString("mode"),
            rs.getString("status"), rs.getLong("revision"), rs.getString("tenant_id"), rs.getString("place_id"),
            rs.getString("product_version_id"), rs.getString("device_id"), rs.getString("external_device_id"),
            rs.getString("external_serial"), rs.getString("device_name"), rs.getString("device_model"),
            rs.getObject("device_type", Integer.class), rs.getObject("communication_type", Integer.class),
            rs.getObject("access_mode", Integer.class), rs.getObject("producter_id", Integer.class),
            rs.getObject("product_model_id", Integer.class), rs.getString("vendor_unit_id"),
            rs.getString("verification_cipher"), rs.getString("verification_nonce"),
            rs.getString("vendor_payload_cipher"), rs.getString("location"),
            rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getObject("auto_add_channel", Boolean.class),
            rs.getString("account_fingerprint"), rs.getString("cloud_state"), rs.getString("cloud_result_json"),
            rs.getString("local_state"), rs.getString("acceptance_note"), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("commissioned_at", OffsetDateTime.class));
    }

    private static DeviceServiceModels.RegistrationSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DeviceServiceModels.RegistrationSummary(rs.getString("id"), rs.getString("driver_key"),
            rs.getString("mode"), rs.getString("status"), rs.getLong("revision"), rs.getString("tenant_id"),
            rs.getString("place_id"), rs.getString("product_version_id"), rs.getString("device_id"),
            rs.getString("external_device_id"), rs.getString("external_serial"), rs.getString("device_name"),
            rs.getString("device_model"), rs.getString("cloud_state"), rs.getString("local_state"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("commissioned_at", OffsetDateTime.class));
    }

    private static DeviceServiceModels.RegistrationSummary summary(Registration registration) {
        return new DeviceServiceModels.RegistrationSummary(registration.id(), registration.driverKey(),
            registration.mode(), registration.status(), registration.revision(), registration.tenantId(),
            registration.placeId(), registration.productVersionId(), registration.deviceId(),
            registration.externalDeviceId(), registration.externalSerial(), registration.deviceName(),
            registration.deviceModel(), registration.cloudState(), registration.localState(),
            registration.createdAt(), registration.updatedAt(), registration.commissionedAt());
    }
}
