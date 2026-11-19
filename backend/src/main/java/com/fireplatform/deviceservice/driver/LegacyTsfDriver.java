package com.fireplatform.deviceservice.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.IntegrationDriver;
import com.fireplatform.deviceservice.LegacySourceConfigService;
import com.fireplatform.deviceservice.VendorLifecycleDriver;
import com.fireplatform.shared.ApiError;
import com.fireplatform.transport.PinnedHttpTransport;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Real TSF smart-fire ledger adapter. The wire fields come from the old platform caller bundle and
 * published endpoint contracts: login {username,password}, Authorization bearer, device get/add/
 * update/delete, getDeviceService, and command {command,deviceId,expireTime,serviceName}.
 *
 * The parent caller sends createAnalysisCommand with those four fields; it does not send dParamList
 * on that path. No TSF tx query endpoint is published, so a submitted command remains ACCEPTED and
 * can never be converted to SUCCEEDED by guessing.
 */
@Component
public class LegacyTsfDriver implements IntegrationDriver, VendorLifecycleDriver {
    public static final String KEY = "LEGACY_TSF";
    public static final String DETAIL_READ = "device.detail.read";
    public static final String SERVICES_READ = "device.services.read";
    public static final String PARAMETERS_READ = "parameters.read";
    public static final String COMMAND_SEND = "command.send";
    public static final String SERVICE_INVOKE = "service.invoke";
    public static final String LEDGER_UPDATE = "ledger.update";
    public static final String CLOUD_DELETE = "cloud.delete";
    private static final Pattern SAFE_TEXT = Pattern.compile("[^\\p{Cntrl}]{0,500}");
    private static final Pattern EXTRA_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final LegacySourceConfigService configuration;
    private final ObjectMapper json;
    private final LegacyWire wire;
    private final List<DriverServiceDescriptor> services;
    private final LegacyApiClient.Transport transport;

    @Autowired
    public LegacyTsfDriver(LegacySourceConfigService configuration, ObjectMapper json) {
        this(configuration, json, PinnedHttpTransport::exchange);
    }

    LegacyTsfDriver(LegacySourceConfigService configuration, ObjectMapper json, LegacyApiClient.Transport transport) {
        this.configuration = configuration;
        this.json = json;
        this.wire = new LegacyWire(json);
        this.transport = java.util.Objects.requireNonNull(transport);
        this.services = List.of(detailRead(), servicesRead(), parametersRead(), commandSend(), serviceInvoke(),
            ledgerUpdate(), cloudDelete());
    }

    @Override public String key() { return KEY; }
    @Override public String mode() { return "VENDOR_PLATFORM"; }
    @Override public String displayName() { return "旧TSF智慧消防台账（真实HTTP接口）"; }
    @Override public boolean configurableOriginAccount() { return true; }
    @Override public List<DriverServiceDescriptor> services() { return services; }

    @Override
    public DriverConfiguration configuration() {
        LegacySourceConfigService.Config current = configuration.load(KEY);
        return new DriverConfiguration(current.configured() && current.enabled(), current.enabled(), current.code(),
            current.origin(), current.usernameMasked(), current.accountFingerprint(), current.revision());
    }

    @Override public String accountFingerprint() { return configuration.load(KEY).accountFingerprint(); }
    @Override public boolean supportsImportedLedgerAssociation() { return true; }

    @Override
    public DriverOutcome submit(DriverCall call) {
        LegacySourceConfigService.Config current = configuration.load(KEY);
        if (!current.configured())
            return DriverOutcome.blocked(current.code(), "旧TSF账号未配置或不可用，未发起外部请求");
        if (!current.enabled())
            return DriverOutcome.blocked("DISABLED", "旧TSF账号未启用，未发起外部请求");
        LegacyApiClient client = new LegacyApiClient(json, current, "Authorization", transport);
        LegacyApiClient.Session session;
        try { session = client.open(); }
        catch (LegacyApiClient.Failure failure) { return DriverOutcome.failed(failure.code(), null, "旧TSF登录失败；未发起设备请求"); }
        boolean mutating = COMMAND_SEND.equals(call.service().key()) || SERVICE_INVOKE.equals(call.service().key())
            || LEDGER_UPDATE.equals(call.service().key()) || CLOUD_DELETE.equals(call.service().key());
        try {
            return switch (call.service().key()) {
                case DETAIL_READ -> detail(call, session);
                case SERVICES_READ -> services(call, session);
                case PARAMETERS_READ -> parameters(call, session);
                case COMMAND_SEND -> command(call, session, false);
                case SERVICE_INVOKE -> command(call, session, true);
                case LEDGER_UPDATE -> ledgerUpdate(call, session);
                case CLOUD_DELETE -> cloudDelete(call, session);
                default -> DriverOutcome.blocked("OPERATION_NOT_IMPLEMENTED", "该操作没有实现的TSF适配器，未发起外部请求");
            };
        } catch (LegacyApiClient.Failure failure) {
            return mutating ? DriverOutcome.unknown(failure.code(), null, "旧TSF写入结果未确认；禁止自动重发")
                : DriverOutcome.failed(failure.code(), null, "旧TSF只读请求失败；未改变设备状态");
        } catch (ApiError invalid) {
            return DriverOutcome.blocked("INVALID_ARGUMENTS", invalid.getMessage());
        }
    }

    @Override
    public DriverOutcome query(DriverCall call) {
        LegacySourceConfigService.Config current = configuration.load(KEY);
        if (!current.configured())
            return DriverOutcome.blocked(current.code(), "旧TSF账号未配置或不可用，未发起外部请求");
        if (!current.enabled())
            return DriverOutcome.blocked("DISABLED", "旧TSF账号未启用，未发起外部请求");
        if (CLOUD_DELETE.equals(call.service().key())) {
            if (call.externalDeviceId() == null || call.externalDeviceId().isBlank())
                return DriverOutcome.blocked("EXTERNAL_ID_MISSING", "缺少待核对的精确台账ID，未发起外部请求");
            LegacyApiClient client = new LegacyApiClient(json, current, "Authorization", transport);
            try {
                return wire.deletionStatus(client.open().get("/api/tsf/device/" + LegacyApiClient.encode(call.externalDeviceId())),
                    call.externalDeviceId(), call.externalSerial(), "/api/tsf/device/{dId}");
            } catch (LegacyApiClient.Failure failure) {
                return DriverOutcome.unknown(failure.code(), null, "台账删除查询未确认；未重发删除");
            }
        }
        return DriverOutcome.unknown("NO_RECEIPT_CONTRACT", null,
            "旧TSF未发布可关联的下发记录/终端回执查询合同；不能把任意事件或网关ACK当作本命令结果");
    }

    // ---------------- reads ----------------

    private DriverOutcome detail(DriverCall call, LegacyApiClient.Session session) {
        if (call.externalDeviceId() == null || call.externalDeviceId().isBlank())
            return DriverOutcome.blocked("EXTERNAL_ID_MISSING", "缺少旧TSF台账设备ID(dId)，未发起外部请求");
        LegacyApiClient.ApiResult result = session.get("/api/tsf/device/" + LegacyApiClient.encode(call.externalDeviceId()));
        if (!result.accepted()) return readFailure(result);
        ObjectNode projected = wire.detail(result.data(), "/api/tsf/device/{dId}");
        return DriverOutcome.succeeded("READ_OK", projected, "旧TSF设备详情读取完成；dAttr/网络密钥已移除");
    }

    private DriverOutcome services(DriverCall call, LegacyApiClient.Session session) {
        if (call.externalDeviceId() == null || call.externalDeviceId().isBlank())
            return DriverOutcome.blocked("EXTERNAL_ID_MISSING", "缺少旧TSF台账设备ID(dId)，未发起外部请求");
        LegacyApiClient.ApiResult result = session.get("/api/tsf/device/getDeviceService/" + LegacyApiClient.encode(call.externalDeviceId()));
        if (!result.accepted()) return readFailure(result);
        ObjectNode projected = wire.services(result.data(), "/api/tsf/device/getDeviceService/{dId}");
        return DriverOutcome.succeeded("READ_OK", projected, "旧TSF服务定义读取完成；保留属性/事件/服务来源分类");
    }

    private DriverOutcome parameters(DriverCall call, LegacyApiClient.Session session) {
        if (call.externalDeviceId() == null || call.externalDeviceId().isBlank())
            return DriverOutcome.blocked("EXTERNAL_ID_MISSING", "缺少旧TSF台账设备ID(dId)，未发起外部请求");
        LegacyApiClient.ApiResult result = session.get("/api/tsf/device/" + LegacyApiClient.encode(call.externalDeviceId()));
        if (!result.accepted()) return readFailure(result);
        ObjectNode projected = wire.parameters(result.data());
        return DriverOutcome.succeeded("READ_OK", projected, "参数来自详情缓存字段；不是实时查询");
    }

    // ---------------- downlink ----------------

    private DriverOutcome command(DriverCall call, LegacyApiClient.Session session, boolean serviceInvoke) {
        JsonNode arguments = call.arguments();
        String command = arguments.path("command").asText("");
        String serviceName = arguments.path("serviceName").asText("");
        int expireTime = arguments.path("expireTime").isIntegralNumber() ? arguments.path("expireTime").asInt() : 1;
        if (serviceInvoke) {
            String params = arguments.path("params").asText("");
            if (params.isBlank()) return DriverOutcome.blocked("INVALID_ARGUMENTS", "服务调用参数为空，未发起外部请求");
            JsonNode parsed;
            try { parsed = json.readTree(params); }
            catch (Exception invalid) { return DriverOutcome.blocked("INVALID_ARGUMENTS", "服务调用参数不是合法JSON，未发起外部请求"); }
            if (parsed == null || !parsed.isObject())
                return DriverOutcome.blocked("INVALID_ARGUMENTS", "服务调用参数必须是JSON对象，未发起外部请求");
            command = parsed.toString();
        }
        if (command.isBlank()) return DriverOutcome.blocked("INVALID_ARGUMENTS", "下发指令为空，未发起外部请求");
        if (command.length() > 2000) return DriverOutcome.blocked("INVALID_ARGUMENTS", "下发指令超过长度限制，未发起外部请求");
        if (serviceName.length() > 100) return DriverOutcome.blocked("INVALID_ARGUMENTS", "服务名称超过长度限制，未发起外部请求");
        if (expireTime < 0 || expireTime > 172800)
            return DriverOutcome.blocked("INVALID_ARGUMENTS", "指令延时必须在0-172800秒之间，未发起外部请求");
        String external = call.externalDeviceId();
        if (external == null || external.isBlank())
            return DriverOutcome.blocked("EXTERNAL_ID_MISSING", "缺少旧TSF台账设备ID(dId)，未发起外部请求");
        LegacyApiClient.ApiResult current;
        try {
            current = session.get("/api/tsf/device/" + LegacyApiClient.encode(external));
        } catch (LegacyApiClient.Failure failure) {
            return DriverOutcome.failed(failure.code(), null, "旧TSF台账详情查询未确认；未下发指令");
        }
        if (current.status() == 404 || (current.status() >= 200 && current.status() < 300 && current.code() == 404))
            return DriverOutcome.failed("LEDGER_NOT_FOUND", wire.sanitizedData(current.root()),
                "旧TSF台账未找到该精确dId；未下发指令");
        if (!current.accepted()) return readFailure(current);
        LegacyWire.RemoteIdentity identity = wire.verify(current.data(), external, call.externalSerial(),
            "/api/tsf/device/{dId}");
        if (!"FOUND".equals(identity.state()))
            return DriverOutcome.failed("SOURCE_IDENTITY_UNCONFIRMED", identity.evidence(), identity.note() + "；未下发指令");
        // TSF callers address the underlying platform: the command deviceId is dDeviceId, while dId is
        // the TSF ledger primary key used only by detail/reset paths. Never send dId here.
        String platformDeviceId = identity.deviceId();
        if (platformDeviceId == null || platformDeviceId.isBlank())
            return DriverOutcome.failed("IDENTIFIER_UNAVAILABLE", identity.evidence(),
                "旧TSF详情缺少dDeviceId（三方平台设备id）；未下发指令");
        ObjectNode body = json.createObjectNode();
        body.put("command", command);
        body.put("deviceId", platformDeviceId);
        body.put("expireTime", expireTime);
        body.put("serviceName", serviceName);
        LegacyApiClient.ApiResult result = session.post("/api/tsf/device/createAnalysisCommand", body);
        if (!result.accepted()) return writeFailure(result);
        ObjectNode evidence = wire.sanitizedData(result.root());
        evidence.put("channel_acceptance_only", true);
        evidence.put("note", "旧TSF接口接受下发；无公开下发记录/终端回执合同，执行结果未确认");
        return DriverOutcome.accepted("ACCEPTED", txReference(result), evidence,
            "台账API接受下发；终端执行未确认，禁止自动重发");
    }

    private DriverOutcome ledgerUpdate(DriverCall call, LegacyApiClient.Session session) {
        JsonNode arguments = call.arguments();
        String name = text(arguments, "name");
        String address = text(arguments, "address");
        String description = text(arguments, "description");
        if (name == null && address == null && description == null)
            return DriverOutcome.blocked("INVALID_ARGUMENTS", "至少提供名称、地址或说明之一，未发起外部请求");
        if (!safe(name) || !safe(address) || !safe(description))
            return DriverOutcome.blocked("INVALID_ARGUMENTS", "台账更新文本含不可接受字符或超过长度，未发起外部请求");
        LegacyApiClient.ApiResult current;
        try {
            current = session.get("/api/tsf/device/" + LegacyApiClient.encode(call.externalDeviceId()));
        } catch (LegacyApiClient.Failure failure) {
            return DriverOutcome.failed(failure.code(), null, "旧TSF台账详情查询未确认；未更新远端台账");
        }
        if (!current.accepted() || current.data() == null || !current.data().isObject())
            return readFailure(current);
        LegacyWire.RemoteIdentity identity = wire.verify(current.data(), call.externalDeviceId(),
            call.externalSerial(), "/api/tsf/device/{dId}");
        if (!"FOUND".equals(identity.state()))
            return DriverOutcome.failed("SOURCE_IDENTITY_UNCONFIRMED", identity.evidence(),
                identity.note() + "；未更新远端台账");
        ObjectNode body = (ObjectNode) current.data().deepCopy();
        body.remove(List.of("dAddTime", "childrenList", "dParam", "deviceParameters",
            "serviceCommond", "serviceName", "serviceParam", "commandType", "loraComandType"));
        if (name != null) body.put("dName", name);
        if (address != null) body.put("dAddress", address);
        if (description != null) body.put("dDetial", description);
        LegacyApiClient.ApiResult result = session.put("/api/tsf/device", body);
        if (!result.accepted()) return writeFailure(result);
        ObjectNode evidence = wire.sanitizedData(result.root());
        evidence.put("note", "旧TSF台账记录已被接口接受更新；本地设备档案未自动改写，终端/NS状态未证明");
        return DriverOutcome.accepted("ACCEPTED", null, evidence,
            "远端台账更新接口接受；响应不代表终端或NS生效，本地元数据不随此命令自动变更");
    }

    private DriverOutcome cloudDelete(DriverCall call, LegacyApiClient.Session session) {
        String external = call.externalDeviceId();
        if (external == null || external.isBlank())
            return DriverOutcome.blocked("EXTERNAL_ID_MISSING", "缺少旧TSF台账设备ID，未发起外部请求");
        LegacyApiClient.ApiResult current;
        try {
            current = session.get("/api/tsf/device/" + LegacyApiClient.encode(external));
        } catch (LegacyApiClient.Failure failure) {
            return DriverOutcome.failed(failure.code(), null, "旧TSF台账详情查询未确认；未删除");
        }
        if (current.status() == 404 || (current.status() >= 200 && current.status() < 300 && current.code() == 404))
            return DriverOutcome.failed("LEDGER_NOT_FOUND", wire.sanitizedData(current.root()),
                "旧TSF台账未找到该精确dId；未删除");
        if (!current.accepted()) return readFailure(current);
        LegacyWire.RemoteIdentity identity = wire.verify(current.data(), external, call.externalSerial(),
            "/api/tsf/device/{dId}");
        if (!"FOUND".equals(identity.state()))
            return DriverOutcome.failed("SOURCE_IDENTITY_UNCONFIRMED", identity.evidence(),
                identity.note() + "；未删除远端台账");
        LegacyApiClient.ApiResult result = session.delete("/api/tsf/device?ids=" + LegacyApiClient.encode(external));
        if (!result.accepted()) return writeFailure(result);
        ObjectNode evidence = wire.sanitizedData(result.root());
        evidence.put("note", "旧TSF台账删除接口接受；不证明NS注销、终端停机或本地归档");
        return DriverOutcome.accepted("ACCEPTED", null, evidence,
            "台账删除被接受；NS注销/终端执行/本地归档均未由此命令证明");
    }

    // ---------------- lifecycle ----------------

    @Override
    public void validateRegistration(CloudRegistration registration, JsonNode vendorPayload) {
        if (vendorPayload == null || !vendorPayload.isObject())
            throw ApiError.bad("旧TSF新增登记必须提供厂商字段对象");
        requireInt(vendorPayload, "productId", "旧TSF产品ID(dProductId)");
        requireInt(vendorPayload, "applicationId", "旧TSF应用ID(dAid)");
        requireInt(vendorPayload, "platformId", "旧TSF平台ID(dPlatform)");
        requireInt(vendorPayload, "networkModel", "旧TSF网络模式(dNetworkModel)");
        requireInt(vendorPayload, "placeId", "旧TSF场所ID(dPid)");
        if (!vendorPayload.path("node").isIntegralNumber() && !vendorPayload.path("parentId").isIntegralNumber())
            throw ApiError.bad("旧TSF新增登记必须提供节点号(dNode)或父设备ID(dParentId)");
        optionalInt(vendorPayload, "companyId", "旧TSF单位ID(dCompanyId)");
        optionalInt(vendorPayload, "node", "旧TSF节点号(dNode)");
        optionalInt(vendorPayload, "parentId", "旧TSF父设备ID(dParentId)");
        requireText(vendorPayload, "address", "旧TSF安装地址(dAddress)", 500);
        requireText(vendorPayload, "gps", "旧TSF GPS坐标(dGps)", 200);
        requireText(vendorPayload, "childSystem", "旧TSF所属系统(dChildSystem)", 100);
        optionalText(vendorPayload, "installMsg", "旧TSF安装信息", 1000);
        JsonNode extra = vendorPayload.get("extra");
        if (extra != null && !extra.isNull()) {
            if (!extra.isObject()) throw ApiError.bad("旧TSF额外字段必须是JSON对象");
            var names = extra.fieldNames();
            while (names.hasNext())
                if (!EXTRA_KEY.matcher(names.next()).matches()) throw ApiError.bad("旧TSF额外字段名无效");
        }
        if (registration.deviceName() == null || registration.deviceName().isBlank()
            || registration.deviceName().length() > 20)
            throw ApiError.bad("旧TSF新增登记设备名称必须是1-20字符");
        String serial = registration.deviceSerial();
        if (serial == null || !serial.matches("[\\x21-\\x7e]{1,200}"))
            throw ApiError.bad("旧TSF设备编号必须是1-200位安全ASCII");
    }

    @Override
    public CloudResult register(CloudRegistration registration, String verificationCode) {
        LegacySourceConfigService.Config current = configuration.load(KEY);
        if (!current.configured()) return new CloudResult("BLOCKED", current.code(), null, null, "旧TSF账号未配置或不可用");
        if (!current.enabled()) return new CloudResult("BLOCKED", "DISABLED", null, null, "旧TSF账号未启用");
        LegacyApiClient client = new LegacyApiClient(json, current, "Authorization", transport);
        LegacyApiClient.Session session;
        try {
            session = client.open();
        } catch (LegacyApiClient.Failure failure) {
            return new CloudResult("FAILED", failure.code(), null, null, "旧TSF登录失败；未发起新增请求");
        }
        try {
            JsonNode payload = registration.vendorPayload();
            ObjectNode body = json.createObjectNode();
            body.put("dName", registration.deviceName());
            body.put("dEui", registration.deviceSerial());
            body.put("dProductId", payload.path("productId").asInt());
            body.put("dAid", payload.path("applicationId").asInt());
            body.put("dPlatform", payload.path("platformId").asInt());
            body.put("dNetworkModel", payload.path("networkModel").asInt());
            body.put("dPid", payload.path("placeId").asInt());
            if (payload.path("node").isIntegralNumber()) body.put("dNode", payload.path("node").asInt());
            if (payload.path("parentId").isIntegralNumber()) body.put("dParentId", payload.path("parentId").asInt());
            if (payload.path("companyId").isIntegralNumber()) body.put("dCompanyId", payload.path("companyId").asInt());
            body.put("dAddress", payload.path("address").asText());
            body.put("dGps", payload.path("gps").asText());
            body.put("dChildSystem", payload.path("childSystem").asText());
            copyText(payload, body, "installMsg", "dInstallMsg");
            copyBoolean(payload, body, "show", "dShow");
            copyBoolean(payload, body, "push", "isPush");
            JsonNode contacts = payload.get("contactIds");
            if (contacts != null && contacts.isArray()) body.set("contactIds", contacts.deepCopy());
            JsonNode extra = payload.get("extra");
            if (extra != null && extra.isObject()) {
                var fields = extra.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!body.has(field.getKey())) body.set(field.getKey(), field.getValue().deepCopy());
                }
            }
            LegacyApiClient.ApiResult result = session.post("/api/tsf/device", body);
            if (!result.accepted())
                return new CloudResult(result.status() >= 400 && result.status() < 500 ? "REJECTED" : "UNKNOWN",
                    result.code() >= 0 ? "CODE_" + result.code() : "HTTP_" + result.status(), null,
                    wire.sanitizedData(result.root()), truncate(result.message(), 300));
            String external = deviceId(result);
            if (external == null)
                return new CloudResult("UNKNOWN", "DEVICE_ID_MISSING", null, wire.sanitizedData(result.root()),
                    "旧TSF add 已被接受但未返回设备ID；需核对台账，不能直接本地验收");
            return new CloudResult("SUCCEEDED", "ADD_ACCEPTED", external, wire.sanitizedData(result.root()),
                "旧TSF台账记录已添加并返回设备ID；产品所需字段差异仍由操作者按型号提供");
        } catch (LegacyApiClient.Failure failure) {
            return new CloudResult("UNKNOWN", failure.code(), null, null, "旧TSF新增请求结果未确认；禁止自动重发");
        }
    }

    @Override
    public LookupResult lookup(String serial, String externalDeviceId) {
        String external = externalDeviceId == null ? null : externalDeviceId.trim();
        if (external == null || external.isBlank())
            return new LookupResult("UNKNOWN", externalDeviceId, null,
                "未提供精确旧TSF台账ID(dId)；列表searchMap序列化未由调用方证据确认，不使用模糊或首行匹配");
        LegacySourceConfigService.Config current = configuration.load(KEY);
        if (!current.configured())
            return new LookupResult("UNKNOWN", external, null, current.code() + ": 旧TSF账号未配置或不可用；未调用远端查询");
        if (!current.enabled())
            return new LookupResult("UNKNOWN", external, null, "旧TSF账号未启用；未调用远端查询");
        LegacyApiClient client = new LegacyApiClient(json, current, "Authorization", transport);
        try {
            LegacyApiClient.Session session = client.open();
            LegacyApiClient.ApiResult result = session.get("/api/tsf/device/" + LegacyApiClient.encode(external));
            return lookupResult(result, serial, external);
        } catch (LegacyApiClient.Failure failure) {
            return new LookupResult("UNKNOWN", external, null,
                "旧TSF设备详情查询未确认（" + failure.code() + "）；不能据此建立关联");
        }
    }

    private LookupResult lookupResult(LegacyApiClient.ApiResult result, String serial, String external) {
        if (result.status() == 404 || (result.status() >= 200 && result.status() < 300 && result.code() == 404))
            return new LookupResult("NOT_FOUND", external, wire.sanitizedData(result.root()),
                "旧TSF台账未找到该精确dId；未建立关联");
        if (!result.accepted()) {
            boolean rejected = result.status() >= 400 && result.status() < 500;
            return new LookupResult(rejected ? "REJECTED" : "UNKNOWN", external, wire.sanitizedData(result.root()),
                rejected ? "旧TSF详情查询被拒绝；未建立关联" : "旧TSF详情查询结果未确认；未建立关联");
        }
        LegacyWire.RemoteIdentity identity = wire.verify(result.data(), external, serial, "/api/tsf/device/{dId}");
        return new LookupResult(identity.state(), identity.ledgerId() == null ? external : identity.ledgerId(),
            identity.evidence(), identity.note());
    }

    @Override
    public CloudResult delete(String externalDeviceId, String serial) {
        LegacySourceConfigService.Config current = configuration.load(KEY);
        if (!current.configured()) return new CloudResult("BLOCKED", current.code(), null, null, "旧TSF账号未配置或不可用");
        if (!current.enabled()) return new CloudResult("BLOCKED", "DISABLED", null, null, "旧TSF账号未启用");
        if (externalDeviceId == null || externalDeviceId.isBlank())
            return new CloudResult("BLOCKED", "EXTERNAL_ID_MISSING", null, null, "缺少旧TSF设备ID");
        if (serial == null || serial.isBlank())
            return new CloudResult("BLOCKED", "EXTERNAL_SERIAL_MISSING", null, null, "缺少旧TSF设备编号，无法核对远端EUI，未删除");
        LegacyApiClient client = new LegacyApiClient(json, current, "Authorization", transport);
        LegacyApiClient.Session session;
        try {
            session = client.open();
        } catch (LegacyApiClient.Failure failure) {
            return new CloudResult("FAILED", failure.code(), null, null, "旧TSF登录失败；未删除");
        }
        LegacyApiClient.ApiResult detail;
        try {
            detail = session.get("/api/tsf/device/" + LegacyApiClient.encode(externalDeviceId));
        } catch (LegacyApiClient.Failure failure) {
            return new CloudResult("FAILED", failure.code(), null, null, "旧TSF详情查询未确认；未删除");
        }
        if (detail.status() == 404 || (detail.status() >= 200 && detail.status() < 300 && detail.code() == 404))
            return new CloudResult("FAILED", "LEDGER_NOT_FOUND", externalDeviceId, wire.sanitizedData(detail.root()),
                "旧TSF台账未找到该精确dId；未删除");
        if (!detail.accepted())
            return new CloudResult(detail.status() >= 400 && detail.status() < 500 ? "REJECTED" : "UNKNOWN",
                detail.code() >= 0 ? "CODE_" + detail.code() : "HTTP_" + detail.status(), null,
                wire.sanitizedData(detail.root()), "旧TSF详情查询未确认，未删除");
        LegacyWire.RemoteIdentity identity = wire.verify(detail.data(), externalDeviceId, serial,
            "/api/tsf/device/{dId}");
        if (!"FOUND".equals(identity.state()))
            return new CloudResult("FAILED", "SOURCE_IDENTITY_UNCONFIRMED", externalDeviceId, identity.evidence(),
                identity.note() + "；未删除");
        LegacyApiClient.ApiResult result;
        try {
            result = session.delete("/api/tsf/device?ids=" + LegacyApiClient.encode(externalDeviceId));
        } catch (LegacyApiClient.Failure failure) {
            return new CloudResult("UNKNOWN", failure.code(), null, null, "旧TSF删除结果未确认；禁止自动重试");
        }
        if (!result.accepted())
            return new CloudResult(result.status() >= 400 && result.status() < 500 ? "REJECTED" : "UNKNOWN",
                result.code() >= 0 ? "CODE_" + result.code() : "HTTP_" + result.status(), null,
                wire.sanitizedData(result.root()), truncate(result.message(), 300));
        return new CloudResult("SUCCEEDED", "DELETE_ACCEPTED", externalDeviceId, wire.sanitizedData(result.root()),
            "旧TSF台账删除接口接受；不证明NS注销或终端停机");
    }

    // ---------------- descriptors ----------------

    private DriverServiceDescriptor detailRead() {
        return descriptor(DETAIL_READ, "读取旧TSF设备详情", "QUERY", "NORMAL", "STATUS", emptySchema(), "VERIFIED",
            "GET /api/tsf/device/{dId}; 详情含deviceParameters/serviceParam缓存；dAttr不返回");
    }
    private DriverServiceDescriptor servicesRead() {
        return descriptor(SERVICES_READ, "读取旧TSF服务定义", "QUERY", "NORMAL", "STATUS", emptySchema(), "VERIFIED",
            "GET /api/tsf/device/getDeviceService/{dId}; 返回17条定义字段；属性/事件/服务区分不从paType猜测");
    }
    private DriverServiceDescriptor parametersRead() {
        return descriptor(PARAMETERS_READ, "读取旧TSF缓存参数", "QUERY", "NORMAL", "PARAMETERS", emptySchema(), "VERIFIED",
            "从GET /api/tsf/device/{dId}的deviceParameters/dParam缓存字段投影；不是实时查询");
    }
    private DriverServiceDescriptor commandSend() {
        ObjectNode schema = schema(List.of(
            stringField("command", "下发指令（原始命令或JSON字符串）", true, 2000),
            stringField("serviceName", "服务名称（可空）", false, 100),
            integerField("expireTime", "指令延时（秒）", false, 0, 172800)));
        return descriptor(COMMAND_SEND, "旧TSF下发指令", "CONTROL", "ENGINEERING", "COMMAND", schema, "VERIFIED",
            "POST /api/tsf/device/createAnalysisCommand; 精确字段{command,deviceId,expireTime,serviceName}来自旧设备详情caller；deviceId取dDeviceId（三方平台设备id），dId仅用于详情/复位；无tx查询/终端回执合同");
    }
    private DriverServiceDescriptor serviceInvoke() {
        ObjectNode schema = schema(List.of(
            stringField("serviceName", "服务名称", true, 100),
            stringField("params", "服务参数JSON对象", true, 4000),
            integerField("expireTime", "指令延时（秒）", false, 0, 172800)));
        return descriptor(SERVICE_INVOKE, "旧TSF服务调用", "CONTROL", "ENGINEERING", "COMMAND", schema, "VERIFIED",
            "command=JSON.stringify(params)后POST createAnalysisCommand；服务名/参数由操作者按设备定义填写；终端执行未确认");
    }
    private DriverServiceDescriptor ledgerUpdate() {
        ObjectNode schema = schema(List.of(
            stringField("name", "台账设备名称（可空）", false, 200),
            stringField("address", "台账地址（可空）", false, 500),
            stringField("description", "台账说明（可空）", false, 500)));
        return descriptor(LEDGER_UPDATE, "更新旧TSF远端台账资料", "CONFIGURATION", "ENGINEERING", "COMMAND", schema, "VERIFIED",
            "先GET详情再PUT /api/tsf/device并移除dAddTime；只改旧台账，不改本地档案、不证明终端生效");
    }
    private DriverServiceDescriptor cloudDelete() {
        ObjectNode schema = schema(List.of(
            stringField("externalDeviceId", "旧TSF台账设备ID", true, 128),
            stringField("serial", "旧TSF设备编号（可空）", false, 200)));
        return descriptor(CLOUD_DELETE, "删除旧TSF远端台账记录", "CONTROL", "ENGINEERING", "COMMAND", schema, "VERIFIED",
            "DELETE /api/tsf/device?ids=<ids>; 仅删除台账记录，不证明NS注销、终端停机或本地归档");
    }

    private DriverServiceDescriptor descriptor(String key, String displayName, String category, String risk,
                                               String resultKind, JsonNode schema, String support, String evidence) {
        ObjectNode evidenceNode = json.createObjectNode();
        evidenceNode.put("wire", evidence);
        evidenceNode.put("execution", "旧TSF未提供可关联的终端执行回执；接口接受不属于终端执行");
        evidenceNode.put("source", "旧TSF前端caller bundle与静态CRUD契约");
        ObjectNode applicability = json.createObjectNode();
        applicability.putArray("models");
        return new DriverServiceDescriptor(key, displayName, category, risk, resultKind, schema, applicability,
            evidenceNode, support, false);
    }

    private ObjectNode emptySchema() { ObjectNode schema = json.createObjectNode(); schema.putArray("fields"); return schema; }

    private ObjectNode schema(List<ObjectNode> fields) {
        ObjectNode schema = json.createObjectNode();
        ArrayNode array = schema.putArray("fields");
        for (ObjectNode field : fields) array.add(field);
        return schema;
    }

    private ObjectNode stringField(String key, String label, boolean required, int maxLength) {
        ObjectNode field = json.createObjectNode();
        field.put("key", key).put("label", label).put("type", "string").put("required", required)
            .put("sensitive", false).put("maxLength", maxLength);
        return field;
    }

    private ObjectNode integerField(String key, String label, boolean required, int minimum, int maximum) {
        ObjectNode field = json.createObjectNode();
        field.put("key", key).put("label", label).put("type", "integer").put("required", required)
            .put("sensitive", false).put("minimum", minimum).put("maximum", maximum);
        return field;
    }

    // ---------------- helpers ----------------

    private DriverOutcome readFailure(LegacyApiClient.ApiResult result) {
        if (result.status() >= 200 && result.status() < 300)
            return DriverOutcome.failed(result.code() >= 0 ? "CODE_" + result.code() : "READ_REJECTED",
                wire.sanitizedData(result.root()), "旧TSF读取被拒绝或返回未确认结构");
        return DriverOutcome.failed("HTTP_" + result.status(), wire.sanitizedData(result.root()), "旧TSF读取HTTP状态未成功");
    }

    private DriverOutcome writeFailure(LegacyApiClient.ApiResult result) {
        if (result.status() >= 400 && result.status() < 500)
            return DriverOutcome.rejected(result.code() >= 0 ? "CODE_" + result.code() : "HTTP_" + result.status(),
                wire.sanitizedData(result.root()), truncate(result.message(), 300));
        return DriverOutcome.unknown(result.code() >= 0 ? "CODE_" + result.code() : "HTTP_" + result.status(),
            wire.sanitizedData(result.root()), "旧TSF写入请求结果未确认；禁止自动重发");
    }

    private static String txReference(LegacyApiClient.ApiResult result) {
        JsonNode data = result.data();
        String value = data == null ? null : firstText(data, "tx_id", "txId", "id");
        if (value == null && result.root() != null) value = firstText(result.root(), "tx_id", "txId");
        return value;
    }

    private static String deviceId(LegacyApiClient.ApiResult result) {
        JsonNode data = result.data();
        String value = data == null ? null : firstText(data, "dId", "d_id", "deviceId", "id");
        if (value == null) value = firstText(result.root(), "dId");
        return value;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) return text;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() || value.asText().isBlank() ? null : value.asText();
    }

    private static boolean safe(String value) {
        return value == null || (value.length() <= 500 && SAFE_TEXT.matcher(value).matches());
    }

    private static String truncate(String value, int max) { return LegacyWire.truncate(value, max); }

    private static int requireInt(JsonNode payload, String key, String label) {
        JsonNode value = payload.get(key);
        if (value == null || !value.isIntegralNumber()) throw ApiError.bad(label + "必须是整数");
        return value.asInt();
    }

    private static void optionalInt(JsonNode payload, String key, String label) {
        JsonNode value = payload.get(key);
        if (value != null && !value.isNull() && !value.isIntegralNumber()) throw ApiError.bad(label + "必须是整数");
    }

    private static void requireText(JsonNode payload, String key, String label, int max) {
        JsonNode value = payload.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
            throw ApiError.bad(label + "必须是长度不超过" + max + "的非空文本");
    }

    private static void optionalText(JsonNode payload, String key, String label, int max) {
        JsonNode value = payload.get(key);
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || value.asText().length() > max) throw ApiError.bad(label + "必须是长度不超过" + max + "的文本");
    }

    private static void copyText(JsonNode payload, ObjectNode body, String source, String target) {
        JsonNode value = payload.get(source);
        if (value != null && value.isTextual() && !value.asText().isBlank()) body.put(target, value.asText());
    }

    private static void copyBoolean(JsonNode payload, ObjectNode body, String source, String target) {
        JsonNode value = payload.get(source);
        if (value != null && value.isBoolean()) body.put(target, value.booleanValue());
    }
}
