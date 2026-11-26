package com.fireplatform.ruleflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reviewed node catalog. Only these types exist; there is no host code, loop, arbitrary HTTP or free-form action node. */
public final class RuleFlowCatalog {
    private RuleFlowCatalog() { }

    public static final Set<String> EVENT_SCOPES = Set.of("FIRE", "FAULT");
    public static final List<String> ANNOTATION_FIELDS = List.of("zone", "position", "eventSource", "time", "eventType");
    /** Exactly the event codes the existing resource-linkage consumer accepts; never a free-form code. */
    public static final List<String> LINKAGE_EVENT_CODES = List.of("FIRE", "200002", "200001", "200014", "200070", "200013", "200017", "200087", "200109", "200040");
    public static final List<String> LINKAGE_ACTIONS = List.of("start", "openGate", "closeGate", "output", "releaser", "reset", "io-start", "io-stop");

    public static final String DEVICE_MESSAGE = "device.message";
    public static final String PERSIST = "persist";
    public static final String CREATE_ALARM = "createalarm";
    public static final String EVENT_TYPE = "eventtype";
    public static final String FAULT = "fault";
    public static final String ANNOTATE = "annotate";
    public static final String PUSH_TYPE = "pushtype";
    public static final String SMS = "sms";
    public static final String VOICE = "voice";
    public static final String WECHAT = "wechat";
    public static final String MERGE = "merge";
    public static final String LINKAGE = "linkage";
    public static final String MQTT = "mqtt";

    /** Fixed native stages: the operator graph cannot remove, reorder or reclassify them. */
    public static final List<String> NATIVE_STAGES = List.of(DEVICE_MESSAGE, PERSIST, CREATE_ALARM);

    /** Nodes that only register a verified read-only reference to an existing consumer. */
    public static final java.util.Set<String> REFERENCE_TYPES = java.util.Set.of(LINKAGE, MQTT);

    public static final Map<String, Set<String>> CONFIG_KEYS = Map.ofEntries(
        Map.entry(DEVICE_MESSAGE, Set.of("scope", "systemTypes")),
        Map.entry(PERSIST, Set.of()),
        Map.entry(CREATE_ALARM, Set.of()),
        Map.entry(EVENT_TYPE, Set.of("fire", "fault")),
        Map.entry(FAULT, Set.of("notify", "silenceMinutes", "voiceEnabled", "voiceTypes")),
        Map.entry(ANNOTATE, Set.of("fields")),
        Map.entry(PUSH_TYPE, Set.of("pushMessage", "periodHours")),
        Map.entry(SMS, Set.of("eventScopes", "receivers", "escalationMinutes")),
        Map.entry(VOICE, Set.of("eventScopes", "receivers", "escalationMinutes")),
        Map.entry(WECHAT, Set.of("eventScopes", "receivers", "escalationMinutes")),
        Map.entry(MERGE, Set.of("enabled", "windowSeconds")),
        Map.entry(LINKAGE, Set.of("ruleId")),
        Map.entry(MQTT, Set.of("applicationId"))
    );

    public static final List<RuleFlowModels.NodeType> TYPES = List.of(
        new RuleFlowModels.NodeType(DEVICE_MESSAGE, "设备消息", "SOURCE", "START", true, 1, 1, true,
            "真实设备事实的入口；FIRE/FAULT 由原生接入链路产生，规则流只选择系统型号与订阅排除项。",
            "原生阶段：接入事实保留。"),
        new RuleFlowModels.NodeType(PERSIST, "持久化", "NATIVE", "FIXED", true, 1, 1, true,
            "原文与标准化事实由既有接入链路持久化，规则流不能关闭。",
            "原生阶段：不可关闭、不可重排。"),
        new RuleFlowModels.NodeType(CREATE_ALARM, "创建报警", "NATIVE", "FIXED", true, 1, 1, true,
            "原生 FIRE/FAULT 会话由既有报警链路创建，本地策略不能抹掉物理风险。",
            "原生阶段：不可关闭、不可重排。"),
        new RuleFlowModels.NodeType(EVENT_TYPE, "事件类型与订阅", "POLICY", "POLICY", false, 0, 1, true,
            "写入报警基础配置的订阅排除项；火灾事件不允许被排除。",
            "消费方：报警基础配置 subscriptions。"),
        new RuleFlowModels.NodeType(FAULT, "故障处理", "POLICY", "POLICY", false, 0, 1, true,
            "写入故障语音播报与本地静音间隔，并决定是否生成故障通知规则。",
            "消费方：报警基础配置 voiceBroadcast/silenceIntervals、通知规则 FAULT。"),
        new RuleFlowModels.NodeType(ANNOTATE, "播报注释", "POLICY", "POLICY", false, 0, 2, true,
            "选择本地语音播报包含的字段；不改变报警事实。",
            "消费方：报警基础配置 voiceBroadcast 字段列表。"),
        new RuleFlowModels.NodeType(PUSH_TYPE, "推送方式与提醒周期", "POLICY", "POLICY", false, 0, 1, true,
            "写入单位通知开关中的消息派发与未恢复提醒周期。",
            "消费方：报警基础配置 unitService/pushRules。"),
        new RuleFlowModels.NodeType(SMS, "短信通道", "NOTIFICATION", "CHANNEL", false, 0, 1, true,
            "生成真实通知规则中的短信接收人与升级间隔，由现有 durable outbox 发送。现有规则服务要求同一事件范围同时具备短信与语音接收人。",
            "消费方：通知规则 receivers(channel=SMS)。"),
        new RuleFlowModels.NodeType(VOICE, "语音通道", "NOTIFICATION", "CHANNEL", false, 0, 1, true,
            "生成真实通知规则中的语音接收人与升级间隔，并启用本地语音播报内容。现有规则服务要求同一事件范围同时具备短信与语音接收人。",
            "消费方：通知规则 receivers(channel=VOICE)。"),
        new RuleFlowModels.NodeType(WECHAT, "微信通道", "NOTIFICATION", "CHANNEL", false, 0, 1, true,
            "只有通知核心声明 WECHAT 通道消费者时才可编译；当前仅有第 1 级接收人，不支持升级间隔。",
            "消费方：通知规则 receivers(channel=WECHAT)，受通道能力声明约束。"),
        new RuleFlowModels.NodeType(MERGE, "报警归并窗口", "POLICY", "POLICY", false, 0, 1, true,
            "写入单位报警归并策略；窗口变化会按既有规则关闭当前已捕获会话，物理报警不受影响。",
            "消费方：报警归并策略。"),
        new RuleFlowModels.NodeType(LINKAGE, "资源联动引用", "REFERENCE", "REFERENCE", false, 0, 4, true,
            "仅登记对既有资源联动规则的引用并核对其真实版本；规则流不改写、不启用联动规则，硬件动作仍由有权人员在联动页面单独维护。",
            "引用：资源联动规则（只读核对）。"),
        new RuleFlowModels.NodeType(MQTT, "历史数据输出引用（已退役）", "REFERENCE", "REFERENCE", false, 0, 4, false,
            "已退役，不可新增或发布。历史定义保留原引用。", "第三方数据输出不再执行。")
    );

    public static RuleFlowModels.NodeType type(String type) {
        for (RuleFlowModels.NodeType item : TYPES) if (item.type().equals(type)) return item;
        return null;
    }

    public static boolean requiresActiveUnitScope(String type) {
        return switch (type) {
            case EVENT_TYPE, FAULT, ANNOTATE, PUSH_TYPE, SMS, VOICE, WECHAT, MERGE -> true;
            default -> false;
        };
    }
}
