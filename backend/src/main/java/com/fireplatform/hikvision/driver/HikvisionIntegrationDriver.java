package com.fireplatform.hikvision.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.IntegrationDriver;
import com.fireplatform.deviceservice.VendorLifecycleDriver;
import com.fireplatform.hikvision.HikvisionClient;
import com.fireplatform.hikvision.HikvisionConfigSecrets;
import com.fireplatform.hikvision.HikvisionConfigService;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hik FireCloud adapter for exactly the published operations: V2 add/delete/operate, V3 deviceConfig,
 * sinkDeviceConfig, deviceCommandResult, V3 getDevice and V2 getOperateState.
 *
 * The portal publishes no 800235 capability matrix, no channel/default-unit rule and no terminal receipt
 * for silence/reset/enable/disable. Those operations are therefore offered as implemented-but-UNVERIFIED
 * descriptors: they cannot dispatch until an operator records verified evidence for the exact model.
 * A vendor code "0" is stored as ACCEPTED, never as physical success.
 */
@Component
public class HikvisionIntegrationDriver implements IntegrationDriver, VendorLifecycleDriver {
    public static final String KEY = "HIKVISION_FIRECLOUD";
    public static final String PARAMETERS_READ = "parameters.read";
    public static final String PARAMETERS_WRITE = "parameters.write";
    public static final String CONTROL_OPERATE = "control.operate";
    public static final String HIK_V1_SILENCE = "hik.v1.silence";
    public static final String RESET = "hik.v2.reset";
    public static final String CLOUD_DELETE = "cloud.delete";
    private static final int LOOKUP_PAGE_SIZE = 10;
    private static final int MAX_LOOKUP_PAGES = 100;
    private static final java.util.regex.Pattern SENSITIVE_PARAMETER =
        java.util.regex.Pattern.compile("(?i).*(password|secret|token|credential|verification.?code).*");

    private final HikvisionClient client;
    private final HikvisionConfigService configuration;
    private final HikvisionConfigSecrets secrets;
    private final ObjectMapper json;
    private final List<DriverServiceDescriptor> services;

    public HikvisionIntegrationDriver(HikvisionClient client, HikvisionConfigService configuration,
                                      HikvisionConfigSecrets secrets, ObjectMapper json) {
        this.client = client;
        this.configuration = configuration;
        this.secrets = secrets;
        this.json = json;
        this.services = List.of(parametersRead(), parametersWrite(), controlOperate(), silenceV1Service(), resetService(), cloudDelete());
    }

    @Override public String key() { return KEY; }
    @Override public String mode() { return "VENDOR_PLATFORM"; }
    @Override public String displayName() { return "海康消防云（官方开放平台）"; }
    @Override public List<DriverServiceDescriptor> services() { return services; }

    @Override
    public DriverConfiguration configuration() {
        HikvisionConfigService.Runtime runtime;
        try { runtime = configuration.refreshRuntime(); }
        catch (RuntimeException unavailable) {
            return new DriverConfiguration(false, false, "CONFIGURATION_UNAVAILABLE", null, null, null, 0);
        }
        boolean credentials = runtime.session().configured();
        String code = !credentials ? "NOT_CONFIGURED" : !runtime.enabled() ? "DISABLED" : "READY";
        return new DriverConfiguration(credentials && runtime.enabled(), runtime.enabled(), code,
            runtime.endpoint(), null, fingerprint(runtime), runtime.revision());
    }

    @Override
    public String accountFingerprint() {
        try { return fingerprint(configuration.refreshRuntime()); }
        catch (RuntimeException unavailable) { return null; }
    }

    private String fingerprint(HikvisionConfigService.Runtime runtime) {
        try {
            return runtime.session().configured() ? secrets.fingerprint(runtime.endpoint() + "\n" + runtime.appKey()) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Override
    public DriverOutcome submit(DriverCall call) {
        DriverConfiguration configuration = configuration();
        if (!configuration.configured())
            return DriverOutcome.blocked(configuration.code(), "海康真实接入未启用或凭证未配置，未发起任何外部请求");
        String external = call.externalDeviceId();
        if (external == null || external.isBlank())
            return DriverOutcome.blocked("DEVICE_NOT_SOURCE_BOUND", "设备没有可用的来源资源标识，未发起外部请求");
        try {
            return switch (call.service().key()) {
                case PARAMETERS_READ -> parametersRead(call, external);
                case PARAMETERS_WRITE -> parametersWrite(call, external);
                case CONTROL_OPERATE -> controlOperate(call, external);
                case HIK_V1_SILENCE -> silenceV1(call, external);
                case RESET -> sendControl(external, json.createObjectNode().put("operateType", 7), null, null);
                case CLOUD_DELETE -> cloudDelete(call, external);
                default -> DriverOutcome.blocked("OPERATION_NOT_IMPLEMENTED", "该操作没有实现的海康适配器，未发起外部请求");
            };
        } catch (HikvisionClient.Failure failure) {
            return fromFailure(failure);
        }
    }

    @Override
    public DriverOutcome query(DriverCall call) {
        DriverConfiguration configuration = configuration();
        if (!configuration.configured())
            return DriverOutcome.blocked(configuration.code(), "海康真实接入未启用或凭证未配置，未发起任何外部请求");
        try {
            return switch (call.service().key()) {
                case PARAMETERS_WRITE -> parametersWriteResult(call);
                case CONTROL_OPERATE -> controlState(call);
                case HIK_V1_SILENCE -> DriverOutcome.unknown("NO_OFFICIAL_RECEIPT_CONTRACT", null,
                    "V1消音响应仅代表指令下发接受/拒绝；官方未发布终端消音回执");
                case CLOUD_DELETE -> absence(call, false, client.session());
                default -> DriverOutcome.unknown("NO_OFFICIAL_RECEIPT_CONTRACT", null,
                    "官方未发布该操作的关联回执查询，不能把读取到的任意设备事件当作本指令结果");
            };
        } catch (HikvisionClient.Failure failure) {
            return fromFailure(failure);
        }
    }

    // ---------------- documented parameter read/write ----------------

    private DriverOutcome parametersRead(DriverCall call, String external) {
        HikvisionClient.VendorResult result = client.write("/api/device/v3/deviceConfig", json.createObjectNode().put("deviceID", external));
        if (!"0".equals(result.code())) return vendorRejection(result);
        JsonNode values = result.data().path("paramValues");
        if (!values.isArray()) return DriverOutcome.unknown("INVALID_PARAMETER_RESPONSE", bounded(result.data()), "厂商返回的参数结构未确认，不能视为读取完成");
        ObjectNode schemaMap = json.createObjectNode();
        ArrayNode masked = json.createArrayNode();
        for (JsonNode value : values) {
            String type = value.path("paramType").asText("");
            JsonNode schema = value.path("schema");
            if (schema.isTextual()) {
                try { schema = json.readTree(schema.asText()); }
                catch (Exception invalid) { schema = null; }
            }
            if (!type.isBlank() && schema != null && schema.isObject()) schemaMap.set(type, schema);
            ObjectNode item = json.createObjectNode();
            if (value.isObject()) item.setAll((ObjectNode) value);
            else item.put("value", value.asText());
            if (SENSITIVE_PARAMETER.matcher(type).matches()) item.put("value", "******");
            masked.add(item);
        }
        ObjectNode out = json.createObjectNode();
        out.set("parameters", masked);
        out.set("schema", schemaMap);
        return DriverOutcome.succeeded("READ_OK", bounded(out), "参数读取完成；schema为设备返回原样，不是平台推测");
    }

    private DriverOutcome parametersWrite(DriverCall call, String external) {
        JsonNode arguments = call.arguments();
        String channelId = arguments.path("channelId").asText("");
        String paramType = arguments.path("paramType").asText("");
        String value = arguments.path("value").asText("");
        if (channelId.isBlank() || paramType.isBlank() || value.isEmpty())
            return DriverOutcome.blocked("INVALID_ARGUMENTS", "通道、参数类型或参数值为空，未发起外部请求");
        ObjectNode body = json.createObjectNode().put("deviceID", external);
        ArrayNode channels = body.putArray("channelList");
        ObjectNode channel = channels.addObject();
        channel.put("channelID", channelId);
        channel.putArray("paramValues").addObject().put("paramType", paramType).put("value", value);
        HikvisionClient.VendorResult result = client.write("/api/device/v3/sinkDeviceConfig", body);
        if (!"0".equals(result.code())) return vendorRejection(result);
        String reference = reference(result.data());
        return reference == null
            ? DriverOutcome.accepted("ACCEPTED_NO_REFERENCE", null, bounded(result.data()), "厂商接受但响应未提供可追踪commandID；需人工核对")
            : DriverOutcome.accepted("ACCEPTED", reference, bounded(result.data()), "厂商接受；等待deviceCommandResult关联结果");
    }

    private DriverOutcome parametersWriteResult(DriverCall call) {
        if (call.reference() == null || call.reference().isBlank())
            return DriverOutcome.unknown("REFERENCE_MISSING", null, "没有可查询的厂商指令ID，无法读取关联结果");
        HikvisionClient.VendorResult result = client.write("/api/device/v3/deviceCommandResult",
            json.createObjectNode().put("commandID", call.reference()));
        if (!"0".equals(result.code())) return vendorRejection(result);
        JsonNode data = result.data();
        String status = text(data.path("commandResult"));
        ObjectNode out = json.createObjectNode();
        out.set("commandResult", data.path("commandResult"));
        out.set("itemResults", data.path("itemResults"));
        return switch (status) {
            case "1" -> DriverOutcome.succeeded("VENDOR_RESULT_SUCCESS", bounded(out), "官方deviceCommandResult报告下发成功");
            case "0" -> DriverOutcome.failed("VENDOR_RESULT_FAILED", bounded(out), "官方deviceCommandResult报告下发失败");
            case "2" -> DriverOutcome.accepted("VENDOR_RESULT_EXECUTING", null, bounded(out), "官方报告执行中，保持ACCEPTED");
            case "3" -> DriverOutcome.accepted("VENDOR_STATE_INITIAL", null, bounded(out), "官方状态3=初始化/未下发，仍可能排队；保持ACCEPTED并显式核对，不能自动重发");
            default -> DriverOutcome.unknown("VENDOR_RESULT_UNKNOWN", bounded(out), "官方结果码未确认，不能推断成功或失败");
        };
    }

    // ---------------- documented control ----------------

    private DriverOutcome controlOperate(DriverCall call, String external) {
        JsonNode arguments = call.arguments();
        JsonNode type = arguments.get("operateType");
        if (type == null || !type.isIntegralNumber())
            return DriverOutcome.blocked("INVALID_ARGUMENTS", "缺少操作类型，未发起外部请求");
        ObjectNode operateParam = json.createObjectNode().put("operateType", type.asInt());
        JsonNode value = arguments.get("value");
        if (value != null && value.isTextual()) operateParam.put("value", value.asText());
        return sendControl(external, operateParam, arguments.path("sensorId").textValue(), arguments.path("channelAbility"));
    }

    private DriverOutcome sendControl(String external, ObjectNode operation, String sensorId, JsonNode channelAbility) {
        ObjectNode body = json.createObjectNode().put("deviceID", external).put("operateParam", operation.toString());
        if (sensorId != null && !sensorId.isBlank()) body.put("sensorID", sensorId);
        if (channelAbility != null && channelAbility.isBoolean()) body.put("channelAbility", channelAbility.booleanValue());
        HikvisionClient.VendorResult result = client.write("/api/device/v2/operate", body);
        if (!"0".equals(result.code())) return vendorRejection(result);
        return DriverOutcome.accepted("ACCEPTED", null, bounded(result.data()),
            "厂商仅接受控制请求；官方未发布该型号终端回执，物理执行未确认");
    }

    private DriverOutcome controlState(DriverCall call) {
        JsonNode type = call.arguments().get("operateType");
        if (type == null || !type.isIntegralNumber() || type.asInt() != 2)
            return DriverOutcome.unknown("NO_OFFICIAL_RECEIPT_CONTRACT", null,
                "官方状态查询仅支持开合闸(operateType=2)；其他控制没有可关联的回执，保持UNKNOWN");
        ObjectNode body = json.createObjectNode().put("operateType", 2);
        body.putArray("resources").addObject().put("deviceID", call.externalDeviceId());
        HikvisionClient.VendorResult result = client.write("/api/businessData/v2/getOperateState", body);
        if (!"0".equals(result.code())) return vendorRejection(result);
        JsonNode data = result.data();
        JsonNode row = null;
        if (data.isArray()) {
            for (JsonNode item : data) if (call.externalDeviceId().equals(item.path("deviceID").asText())) { row = item; break; }
        }
        if (row == null) return DriverOutcome.unknown("STATE_ROW_MISSING", bounded(data), "官方状态响应未包含该设备，不能推断结果");
        String status = text(row.path("result"));
        ObjectNode out = json.createObjectNode();
        out.set("result", row.path("result"));
        out.set("currentState", row.path("currentState"));
        out.set("updateTime", row.path("updateTime"));
        return DriverOutcome.unknown(status == null ? "OPERATE_STATE_UNKNOWN" : "OPERATE_STATE_UNCORRELATED",
            bounded(out), "设备级操作状态没有命令引用或新鲜度关联，不能作为当前命令终态回执");
    }

    private DriverOutcome silenceV1(DriverCall call, String external) {
        ObjectNode body = json.createObjectNode().put("id", external).put("operateType", 1);
        HikvisionClient.VendorResult result = client.write("/api/bws/device/v1/device/operate", body);
        if (!"0".equals(result.code())) return vendorRejection(result);
        JsonNode dispatched = result.data();
        if (dispatched.isBoolean()) {
            return dispatched.booleanValue()
                ? DriverOutcome.accepted("DISPATCH_ACCEPTED", null, bounded(result.data()),
                    "V1消音指令下发被接受；不代表终端已消音，官方未发布回执")
                : DriverOutcome.rejected("DISPATCH_REJECTED", bounded(result.data()), "V1消音指令下发被拒绝");
        }
        return DriverOutcome.unknown("DISPATCH_RESULT_UNKNOWN", bounded(result.data()), "V1消音响应未提供明确布尔结果，不能推断成功");
    }

    // ---------------- documented cloud delete + absence reconciliation ----------------

    private DriverOutcome cloudDelete(DriverCall call, String external) {
        var session = client.session();
        HikvisionClient.VendorResult result = session.write("/api/device/v2/delete", json.createObjectNode().put("deviceID", external));
        if (!"0".equals(result.code())) return vendorRejection(result);
        return absence(call, true, session);
    }

    private DriverOutcome absence(DriverCall call, boolean justDeleted, HikvisionClient.Session session) {
        String external = call.externalDeviceId();
        if (external == null || external.isBlank())
            return DriverOutcome.unknown("CLOUD_ABSENCE_UNVERIFIED", null, "缺少绑定的云设备标识，无法确认云端已无记录");
        LookupResult lookup = lookup(session, call.externalSerial(), external);
        ObjectNode out = json.createObjectNode();
        out.put("external_device_id", external);
        out.put("lookup_state", lookup.state());
        out.set("lookup_result", lookup.result());
        String note = justDeleted ? "厂商已接受删除；仅云端记录效果，不代表终端断电或注销" : "云端删除状态核对";
        return switch (lookup.state()) {
            case "NOT_FOUND" -> DriverOutcome.succeeded("CLOUD_ABSENCE_VERIFIED", bounded(out), note + "；授权查询确认已无该云记录");
            case "FOUND" -> DriverOutcome.accepted("CLOUD_RECORD_PRESENT", null, bounded(out), note + "；仍可查询到云记录，未确认删除");
            default -> DriverOutcome.accepted("CLOUD_ABSENCE_UNVERIFIED", null, bounded(out), note + "；无法确认云端已无记录，需人工核对");
        };
    }

    // ---------------- vendor lifecycle ----------------

    @Override
    public CloudResult register(CloudRegistration registration, String verificationCode) {
        if (!configuration().configured())
            return new CloudResult("BLOCKED", configuration().code(), null, null, "海康真实接入未启用或凭证未配置，未发起任何外部请求");
        ObjectNode body = json.createObjectNode();
        body.put("deviceName", registration.deviceName());
        body.put("deviceSerial", registration.deviceSerial());
        body.put("deviceModel", registration.deviceModel());
        if (registration.deviceType() != null) body.put("deviceType", registration.deviceType());
        if (registration.communicationType() != null) body.put("communicationType", registration.communicationType());
        if (verificationCode != null && !verificationCode.isBlank()) body.put("verificationCode", verificationCode);
        if (registration.producterId() != null) body.put("producterID", registration.producterId());
        if (registration.productModelId() != null) body.put("productModelID", registration.productModelId());
        if (registration.location() != null && !registration.location().isBlank()) body.put("location", registration.location());
        if (registration.autoAddChannel() != null) body.put("autoAddChannel", registration.autoAddChannel());
        if (registration.vendorUnitId() != null && !registration.vendorUnitId().isBlank()) body.put("unitID", registration.vendorUnitId());
        if (registration.latitude() != null) body.put("lat", registration.latitude());
        if (registration.longitude() != null) body.put("lon", registration.longitude());
        try {
            HikvisionClient.VendorResult result = client.write("/api/device/v2/add", body);
            if (!"0".equals(result.code()))
                return new CloudResult("REJECTED", result.code(), null, bounded(result.data()), "厂商拒绝注册：" + result.msg());
            String deviceId = result.data().path("deviceID").asText("");
            ObjectNode out = json.createObjectNode();
            if (!deviceId.isBlank()) out.put("deviceID", deviceId);
            if (result.data().path("channelOutVOS").isArray()) out.put("channelCount", result.data().path("channelOutVOS").size());
            if (deviceId.isBlank())
                return new CloudResult("UNKNOWN", "VENDOR_ID_MISSING", null, bounded(out),
                    "厂商返回成功码但未提供deviceID，云记录可能存在；请按序列号核对，禁止重复注册");
            return new CloudResult("SUCCEEDED", "ACCEPTED", deviceId, bounded(out),
                "厂商接受新增并返回云设备标识；不代表终端激活、上线或已本地开户");
        } catch (HikvisionClient.Failure failure) {
            return new CloudResult(mapFailure(failure), failure.code(), null, null,
                "新增请求结果未确认；必须按序列号查询核对，禁止直接重复新增或云删除补偿");
        }
    }

    @Override
    public LookupResult lookup(String serial, String externalDeviceId) {
        return lookup(client.session(), serial, externalDeviceId);
    }

    private LookupResult lookup(HikvisionClient.Session session, String serial, String externalDeviceId) {
        if (serial == null || serial.isBlank())
            return new LookupResult("UNKNOWN", null, null, "缺少可用于精确查询的序列号");
        boolean knownId = externalDeviceId != null && !externalDeviceId.isBlank();
        String filter = knownId ? "deviceID" : "deviceSerial";
        String value = knownId ? externalDeviceId : serial;
        var seenIds = new HashSet<String>();
        JsonNode match = null;
        long total = -1;
        String flag = "";
        try {
            for (int page = 1; page <= MAX_LOOKUP_PAGES; page++) {
                ObjectNode body = json.createObjectNode().put(filter, value)
                    .put("pageNo", Integer.toString(page)).put("pageSize", Integer.toString(LOOKUP_PAGE_SIZE))
                    .put("flagId", flag).put("offset", page == 1 ? "0" : "1");
                HikvisionClient.VendorResult result = session.write("/api/device/v3/getDevice", body);
                if (!"0".equals(result.code()))
                    return new LookupResult("REJECTED", null, bounded(result.data()), "厂商拒绝查询：" + result.msg());
                JsonNode data = result.data();
                JsonNode list = data.path("list"), reportedTotal = data.path("total"), reportedPage = data.path("pageNo");
                if (!list.isArray() || !reportedTotal.isIntegralNumber() || !reportedTotal.canConvertToLong()
                    || !reportedPage.isIntegralNumber() || !reportedPage.canConvertToInt() || reportedPage.intValue() != page)
                    return incompleteLookup();
                if (page == 1) {
                    total = reportedTotal.longValue();
                    if (total > (long) LOOKUP_PAGE_SIZE * MAX_LOOKUP_PAGES) return incompleteLookup();
                }
                if (total != reportedTotal.longValue() || total < (long) seenIds.size() + list.size()
                    || (list.isEmpty() && seenIds.size() < total))
                    return incompleteLookup();

                for (JsonNode row : list) {
                    String id = row.path("deviceID").textValue(), rowSerial = row.path("deviceSerial").textValue();
                    if (id == null || id.isBlank() || rowSerial == null || rowSerial.isBlank() || !seenIds.add(id))
                        return incompleteLookup();
                    if (knownId && !externalDeviceId.equals(id)) continue;
                    if (!serial.equals(rowSerial)) {
                        if (knownId) return new LookupResult("UNKNOWN", null, null, "云设备标识和绑定序列号不一致");
                        continue;
                    }
                    if (match != null)
                        return new LookupResult("AMBIGUOUS", null, null, "精确序列号匹配到多条云资源，需人工核对");
                    match = row;
                }
                if (seenIds.size() == total) {
                    if (match != null)
                        return new LookupResult("FOUND", match.path("deviceID").textValue(), bounded(match), "精确匹配到唯一云资源");
                    return new LookupResult("NOT_FOUND", null, json.createObjectNode().put("total", total).put("pages_checked", page),
                        "授权查询完整返回，未发现匹配云资源");
                }
                flag = data.path("flagId").asText("");
            }
            return incompleteLookup();
        } catch (HikvisionClient.Failure failure) {
            return new LookupResult(mapFailure(failure), null, null, "查询结果未确认");
        }
    }

    private static LookupResult incompleteLookup() {
        return new LookupResult("UNKNOWN", null, null, "云端分页响应不完整或记录身份不一致，不能核实查找结果");
    }

    @Override
    public CloudResult delete(String externalDeviceId, String serial) {
        if (!configuration().configured())
            return new CloudResult("BLOCKED", configuration().code(), externalDeviceId, null, "海康真实接入未启用或凭证未配置，未发起任何外部请求");
        if (externalDeviceId == null || externalDeviceId.isBlank())
            return new CloudResult("BLOCKED", "EXTERNAL_ID_MISSING", null, null, "缺少云设备标识，未发起任何外部请求");
        try {
            var session = client.session();
            HikvisionClient.VendorResult result = session.write("/api/device/v2/delete", json.createObjectNode().put("deviceID", externalDeviceId));
            if (!"0".equals(result.code()))
                return new CloudResult("REJECTED", result.code(), externalDeviceId, bounded(result.data()), "厂商拒绝删除：" + result.msg());
            LookupResult lookup = lookup(session, serial, externalDeviceId);
            return switch (lookup.state()) {
                case "NOT_FOUND" -> new CloudResult("SUCCEEDED", "CLOUD_ABSENCE_VERIFIED", externalDeviceId, bounded(lookup.result()),
                    "厂商接受删除且授权查询确认已无该云记录；不代表终端断电或注销");
                case "FOUND" -> new CloudResult("ACCEPTED", "CLOUD_RECORD_PRESENT", externalDeviceId, bounded(lookup.result()),
                    "删除后仍可查询到云记录，未确认删除");
                default -> new CloudResult("ACCEPTED", "CLOUD_ABSENCE_UNVERIFIED", externalDeviceId, bounded(lookup.result()),
                    "删除已接受但无法确认云端已无记录");
            };
        } catch (HikvisionClient.Failure failure) {
            return new CloudResult(mapFailure(failure), failure.code(), externalDeviceId, null, "删除请求结果未确认，禁止自动重发");
        }
    }

    // ---------------- helpers ----------------

    private DriverOutcome vendorRejection(HikvisionClient.VendorResult result) {
        String code = result.code();
        String normalized = code != null && (code.matches("0x[0-9A-Fa-f]{8}") || code.matches("[1-9][0-9]{0,5}")) ? code : "UPSTREAM_ERROR";
        if ("UPSTREAM_ERROR".equals(normalized))
            return DriverOutcome.unknown(normalized, bounded(result.data()), "厂商返回非标准错误码，请求结果未确认");
        return DriverOutcome.rejected(normalized, bounded(result.data()), "厂商拒绝：" + result.msg());
    }

    private DriverOutcome fromFailure(HikvisionClient.Failure failure) {
        String code = failure.code();
        return switch (mapFailure(failure)) {
            case "BLOCKED" -> DriverOutcome.blocked(code, "驱动未配置或请求体无效，未发起外部请求");
            default -> DriverOutcome.unknown(code, null, "网络/响应未确认；禁止自动重发，可显式核对");
        };
    }

    private static String mapFailure(HikvisionClient.Failure failure) {
        return switch (failure.code()) {
            case "NOT_CONFIGURED", "WRITE_PATH_NOT_ALLOWED", "INVALID_REQUEST_BODY" -> "BLOCKED";
            case "NETWORK_OR_RESPONSE_ERROR", "INTERRUPTED", "RESPONSE_TOO_LARGE", "INVALID_RESPONSE", "UPSTREAM_ERROR" -> "UNKNOWN";
            default -> failure.code().startsWith("HTTP_") ? "UNKNOWN" : failure.code();
        };
    }

    private static String reference(JsonNode data) {
        if (data == null) return null;
        if (data.isTextual() && !data.asText().isBlank()) return data.asText();
        for (String key : List.of("commandID", "commandId", "taskId", "id")) {
            JsonNode value = data.path(key);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
            if (value.isIntegralNumber()) return value.asText();
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        return node.isTextual() ? node.asText() : node.asText("");
    }

    private JsonNode bounded(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        String encoded = node.toString();
        if (encoded.length() <= 65536) return node;
        ObjectNode truncated = json.createObjectNode();
        truncated.put("truncated", true);
        truncated.put("original_bytes", encoded.length());
        return truncated;
    }

    // ---------------- built-in descriptors ----------------

    private DriverServiceDescriptor parametersRead() {
        return service(PARAMETERS_READ, "读取设备参数", "QUERY", "NORMAL", "PARAMETERS",
            schema(), applicability("V3 deviceConfig 为官方通用读取；返回schema以设备实际返回为准"),
            evidence("https://open.hikfirecloud.com/artemis-portal/document/102 与 /document/76",
                "V3 deviceConfig返回paramValues[].schema；读取不改变设备状态"), "VERIFIED");
    }

    private DriverServiceDescriptor parametersWrite() {
        return service(PARAMETERS_WRITE, "下发设备参数", "CONFIGURATION", "ENGINEERING", "COMMAND",
            schema(
                field("channelId", "通道ID", "string", true, false, null, null, 64, List.of()),
                field("paramType", "参数类型", "string", true, false, null, null, 64, List.of()),
                field("value", "参数值", "string", true, false, null, null, 256, List.of())),
            applicability("官方未发布800235参数集、通道或默认单元规则；通道必须由操作者依证据填写"),
            evidence("https://open.hikfirecloud.com/artemis-portal/document/102",
                "sinkDeviceConfig要求channelList；deviceCommandResult状态 0失败/1成功/2执行中/3未下发"),
            "UNVERIFIED");
    }

    private DriverServiceDescriptor controlOperate() {
        return service(CONTROL_OPERATE, "设备控制（消音/复位/屏蔽/启停等）", "CONTROL", "ENGINEERING", "COMMAND",
            schema(
                field("operateType", "操作类型", "integer", true, false, java.math.BigDecimal.ONE, java.math.BigDecimal.valueOf(14), null, List.of()),
                field("value", "操作值", "string", false, false, null, null, 64, List.of()),
                field("sensorId", "传感器ID", "string", false, false, null, null, 128, List.of()),
                field("channelAbility", "按能力下发", "boolean", false, false, null, null, null, List.of())),
            applicability("官方仅发布通用操作字典，未声明800235支持消音/复位/屏蔽/启停或终端回执"),
            evidence("https://open.hikfirecloud.com/artemis-portal/document/85 与 /document/65",
                "V2 operate响应仅为厂商接受；getOperateState官方仅支持开合闸，code0不是物理执行成功"),
            "UNVERIFIED");
    }

    private DriverServiceDescriptor silenceV1Service() {
        return service(HIK_V1_SILENCE, "设备消音（V1，普通操作）", "CONTROL", "NORMAL", "COMMAND",
            schema(), applicability("V1通道消音；官方未发布800235消音能力与终端回执"),
            evidence("https://open.hikfirecloud.com/artemis-portal/document/85",
                "V1 operate operateType=1；data true=指令下发接受，false=拒绝；无终端回执"),
            "UNVERIFIED", true);
    }

    private DriverServiceDescriptor resetService() {
        return service(RESET, "设备复位", "CONTROL", "ENGINEERING", "COMMAND", schema(),
            applicability("仅在当前型号已有复位能力证据时可下发"),
            evidence("https://open.hikfirecloud.com/artemis-portal/document/65 与 /document/85",
                "设备操作命令 7=复位；接受不是终端复位回执"), "UNVERIFIED");
    }

    private DriverServiceDescriptor cloudDelete() {
        return service(CLOUD_DELETE, "删除云端设备记录", "CONTROL", "ENGINEERING", "COMMAND",
            schema(
                field("externalDeviceId", "云设备ID", "string", true, false, null, null, 128, List.of()),
                field("serial", "设备序列号", "string", false, false, null, null, 200, List.of())),
            applicability("仅云记录删除效果；不代表终端断电、注销或解绑成功"),
            evidence("https://open.hikfirecloud.com/artemis-portal/document/75",
                "V2 delete仅返回通用成功；删除后必须用V3 getDevice按精确序列号核对不存在"),
            "VERIFIED");
    }

    private DriverServiceDescriptor service(String key, String displayName, String category, String riskLevel,
                                            String resultKind, JsonNode schema, JsonNode applicability,
                                            JsonNode evidence, String supportState) {
        return service(key, displayName, category, riskLevel, resultKind, schema, applicability, evidence, supportState, false);
    }

    private DriverServiceDescriptor service(String key, String displayName, String category, String riskLevel,
                                            String resultKind, JsonNode schema, JsonNode applicability,
                                            JsonNode evidence, String supportState, boolean typedNormalAllowed) {
        return new DriverServiceDescriptor(key, displayName, category, riskLevel, resultKind, schema,
            applicability, evidence, supportState, typedNormalAllowed);
    }

    private JsonNode schema(DriverField... fields) {
        ObjectNode schema = json.createObjectNode();
        ArrayNode list = schema.putArray("fields");
        for (DriverField field : fields) {
            ObjectNode item = list.addObject();
            item.put("key", field.key()).put("label", field.label()).put("type", field.type())
                .put("required", field.required()).put("sensitive", field.sensitive());
            if (field.minimum() != null) item.put("minimum", field.minimum());
            if (field.maximum() != null) item.put("maximum", field.maximum());
            if (field.maxLength() != null) item.put("maxLength", field.maxLength());
            if (!field.allowed().isEmpty()) {
                ArrayNode allowed = item.putArray("enum");
                field.allowed().forEach(allowed::add);
            }
        }
        return schema;
    }

    private JsonNode applicability(String note) {
        ObjectNode node = json.createObjectNode();
        node.putArray("models").add("800235");
        node.put("note", note);
        return node;
    }

    private JsonNode evidence(String basis, String note) {
        ObjectNode node = json.createObjectNode();
        node.put("basis", basis);
        node.put("note", note);
        node.put("acceptance", "code 0 = vendor API acceptance only");
        return node;
    }

    private static DriverField field(String key, String label, String type, boolean required, boolean sensitive,
                                     java.math.BigDecimal minimum, java.math.BigDecimal maximum, Integer maxLength,
                                     List<String> allowed) {
        return new DriverField(key, label, type, required, sensitive, minimum, maximum, maxLength, allowed);
    }

    private record DriverField(String key, String label, String type, boolean required, boolean sensitive,
                               java.math.BigDecimal minimum, java.math.BigDecimal maximum, Integer maxLength,
                               List<String> allowed) { }
}
