package com.fireplatform.ruleflow;

import com.fireplatform.alarmgroup.AlarmMergePolicyQueries;
import com.fireplatform.configuration.AlarmBaseConfigurationQueries;
import com.fireplatform.configuration.ResourceLinkageCommands;
import com.fireplatform.identity.AuthService;
import com.fireplatform.notification.NotificationChannelSupport;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.Digests;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Pure compiler: validates a graph against the reviewed catalog and produces the exact typed-consumer targets
 * plus the dependency revisions observed at compile time. It writes nothing; the command layer applies the plan
 * only after session, permission, tenant and dependency checks pass inside one transaction.
 */
@Service
public class RuleFlowCompiler {
    private static final Set<String> SCOPE_KEYS = Set.of("mode", "placeIds", "deviceIds");
    private static final Set<String> TYPE_SCOPE_KEYS = Set.of("fire", "fault");
    private static final Set<String> RECEIVER_KEYS = Set.of("contactId", "level");

    private final JdbcTemplate jdbc;
    private final AlarmBaseConfigurationQueries base;
    private final AlarmMergePolicyQueries merge;
    private final ResourceLinkageCommands linkage;
    private final DeviceAccessScope devices;
    private final NotificationChannelSupport channels;

    public RuleFlowCompiler(JdbcTemplate jdbc, AlarmBaseConfigurationQueries base, AlarmMergePolicyQueries merge,
            ResourceLinkageCommands linkage, DeviceAccessScope devices, NotificationChannelSupport channels) {
        this.jdbc = jdbc; this.base = base; this.merge = merge; this.linkage = linkage; this.devices = devices; this.channels = channels;
    }

    /** Everything the compiler observes read-only. The command layer re-observes it under row locks before writing. */
    public record Observed(Map<String, Object> baseValue, long baseRevision, long mergeRevision,
                           boolean mergeEnabled, Integer mergeWindow, Map<String, RuleFlowModels.Binding> bindings) { }

    public static String bindingKey(String consumerKind, String scopeKey) { return consumerKind + ":" + scopeKey; }

    /** Read-only snapshot of every consumer the compiler needs. Callers re-read it under row locks before writing. */
    public Observed observe(String tenant, Map<String, RuleFlowModels.Binding> bindings) {
        var configuration = base.configuration(tenant);
        var policy = merge.policy(tenant);
        return new Observed(configuration.value(), configuration.revision(), policy.revision(),
            policy.enabled(), policy.windowSeconds(), bindings);
    }

    public RuleFlowModels.Plan compile(AuthService.Identity actor, String tenant, String flowId, String flowName,
            RuleFlowModels.Definition definition, long draftRevision, Observed observed) {
        List<RuleFlowModels.Issue> issues = new ArrayList<>();
        validateGraph(definition, issues);
        Map<String, Object> baseValue = new LinkedHashMap<>(observed.baseValue());
        List<String> owned = new ArrayList<>();
        List<RuleFlowModels.Diff> diffs = new ArrayList<>();
        List<RuleFlowModels.Dependency> dependencies = new ArrayList<>();
        List<RuleFlowModels.RuleTarget> rules = new ArrayList<>();
        List<RuleFlowModels.Reference> references = new ArrayList<>();
        RuleFlowModels.MergeTarget mergeTarget = null;

        boolean policyNodes = definition.nodes().stream().anyMatch(item -> RuleFlowCatalog.requiresActiveUnitScope(item.type()));
        String scopeMode = "ALL";
        List<String> systemTypes = List.of();
        List<String> faultExcludes = List.of();
        boolean faultNotify = false;
        String faultNodeId = null, pushNodeId = null;
        int faultSilence = 0;
        boolean faultVoice = false;
        List<String> faultVoiceTypes = List.of();
        List<String> annotationFields = new ArrayList<>();
        Integer pushPeriod = null;
        Boolean pushMessage = null;
        Map<String, List<Map<String, Object>>> receivers = new LinkedHashMap<>();
        Map<String, Long> escalation = new LinkedHashMap<>();
        for (String scope : RuleFlowCatalog.EVENT_SCOPES) receivers.put(scope, new ArrayList<>());

        for (RuleFlowModels.Node node : definition.nodes()) {
            RuleFlowModels.NodeType type = RuleFlowCatalog.type(node.type());
            if (type == null) { issues.add(error(node, "NODE_UNKNOWN", "节点类型不在受审目录内：" + node.type())); continue; }
            if (!type.supported() && !RuleFlowCatalog.REFERENCE_TYPES.contains(node.type())) {
                issues.add(error(node, "NODE_UNSUPPORTED", type.rationale()));
                continue;
            }
            Set<String> unknown = new LinkedHashSet<>(node.config().keySet());
            unknown.removeAll(RuleFlowCatalog.CONFIG_KEYS.getOrDefault(node.type(), Set.of()));
            if (!unknown.isEmpty()) { issues.add(error(node, "NODE_CONFIG_FIELD", "节点包含不支持的配置字段：" + String.join(",", unknown))); continue; }
            switch (node.type()) {
                case RuleFlowCatalog.DEVICE_MESSAGE -> {
                    Scope scope = scope(actor, tenant, node, policyNodes, issues);
                    if (scope != null) scopeMode = scope.mode();
                    systemTypes = stringList(node, node.config().get("systemTypes"), 32, 64, "系统型号列表", issues);
                }
                case RuleFlowCatalog.PERSIST, RuleFlowCatalog.CREATE_ALARM -> { }
                case RuleFlowCatalog.EVENT_TYPE -> {
                    Map<String, Object> fire = section(node, "fire", Set.of("excludeCodes"), issues);
                    Map<String, Object> fault = section(node, "fault", Set.of("excludeCodes"), issues);
                    if (fire != null) {
                        List<String> excluded = stringList(node, fire.get("excludeCodes"), 64, 64, "火灾排除事件代码", issues);
                        if (!excluded.isEmpty()) issues.add(error(node, "FIRE_EXCLUSION_FORBIDDEN", "火灾事件的通知不能被规则流排除；物理风险通知必须保留。"));
                    }
                    if (fault != null) faultExcludes = stringList(node, fault.get("excludeCodes"), 64, 64, "故障排除事件代码", issues);
                }
                case RuleFlowCatalog.FAULT -> {
                    faultNodeId = node.id();
                    Boolean notify = bool(node, node.config().get("notify"), "故障通知开关", issues);
                    Integer silence = integer(node, node.config().get("silenceMinutes"), 0, 1440, "故障静音间隔", issues);
                    Boolean voice = bool(node, node.config().get("voiceEnabled"), "故障播报开关", issues);
                    faultNotify = Boolean.TRUE.equals(notify);
                    faultSilence = silence == null ? 0 : silence;
                    faultVoice = Boolean.TRUE.equals(voice);
                    faultVoiceTypes = stringList(node, node.config().get("voiceTypes"), 32, 64, "故障播报类型", issues);
                }
                case RuleFlowCatalog.ANNOTATE -> {
                    List<String> fields = stringList(node, node.config().get("fields"), 5, 32, "播报字段", issues);
                    for (String field : fields) {
                        if (!RuleFlowCatalog.ANNOTATION_FIELDS.contains(field)) { issues.add(error(node, "ANNOTATION_FIELD", "不支持的播报字段：" + field)); continue; }
                        if (!annotationFields.contains(field)) annotationFields.add(field);
                    }
                }
                case RuleFlowCatalog.PUSH_TYPE -> {
                    pushNodeId = node.id();
                    pushMessage = bool(node, node.config().get("pushMessage"), "消息派发开关", issues);
                    pushPeriod = integer(node, node.config().get("periodHours"), 1, 720, "未恢复提醒周期", issues);
                }
                case RuleFlowCatalog.SMS, RuleFlowCatalog.VOICE, RuleFlowCatalog.WECHAT -> {
                    String channel = node.type().equals(RuleFlowCatalog.SMS) ? "SMS" : node.type().equals(RuleFlowCatalog.VOICE) ? "VOICE" : "WECHAT";
                    if (!channels.supported().contains(channel)) { issues.add(error(node, "CHANNEL_UNSUPPORTED", channels.rationale(channel))); continue; }
                    List<String> scopes = stringList(node, node.config().get("eventScopes"), 2, 8, "事件范围", issues);
                    if (scopes.isEmpty()) scopes = List.of("FIRE");
                    for (String scope : scopes) if (!RuleFlowCatalog.EVENT_SCOPES.contains(scope)) issues.add(error(node, "EVENT_SCOPE_INVALID", "不支持的事件范围：" + scope));
                    List<Map<String, Object>> contacts = receivers(node, tenant, channel, issues);
                    if (contacts.isEmpty()) { issues.add(error(node, "CHANNEL_WITHOUT_RECEIVER", "通道必须至少选择一个真实联系人。")); continue; }
                    Integer minutes = integer(node, node.config().get("escalationMinutes"), 0, 60, "升级间隔", issues);
                    long interval = minutes == null ? 0 : minutes;
                    if ("WECHAT".equals(channel) && interval != 0)
                        issues.add(error(node, "WECHAT_ESCALATION_UNSUPPORTED", "微信通道没有独立升级间隔消费者；请把升级间隔设为 0。"));
                    if ("WECHAT".equals(channel) && contacts.stream().anyMatch(item -> ((Number) item.get("level")).longValue() > 1))
                        issues.add(error(node, "WECHAT_LEVEL_UNSUPPORTED", "微信通道当前只有第 1 级接收人消费者，不能配置多级。"));
                    if ("WECHAT".equals(channel)) interval = 0;
                    for (String scope : scopes) {
                        if (!RuleFlowCatalog.EVENT_SCOPES.contains(scope)) continue;
                        Long previous = escalation.get(scope + ":" + channel);
                        if (previous != null && previous != interval) issues.add(error(node, "ESCALATION_AMBIGUOUS", "同一事件范围的同一通道存在不同升级间隔，无法确定唯一消费者值。"));
                        escalation.put(scope + ":" + channel, interval);
                        receivers.get(scope).addAll(contacts);
                    }
                }
                case RuleFlowCatalog.MERGE -> {
                    Boolean enabled = bool(node, node.config().get("enabled"), "归并开关", issues);
                    Integer window = integer(node, node.config().get("windowSeconds"), 1, 604800, "归并窗口秒数", issues);
                    if (Boolean.TRUE.equals(enabled) && window == null) issues.add(error(node, "MERGE_WINDOW_REQUIRED", "启用报警归并必须明确窗口秒数。"));
                    mergeTarget = new RuleFlowModels.MergeTarget(Boolean.TRUE.equals(enabled), window);
                }
                case RuleFlowCatalog.LINKAGE -> {
                    String ruleId = text(node, node.config().get("ruleId"), 64, "联动规则", issues);
                    if (ruleId == null) continue;
                    ResourceLinkageCommands.Rule rule = linkage.list(tenant).stream().filter(item -> item.id().equals(ruleId)).findFirst().orElse(null);
                    if (rule == null) { issues.add(error(node, "LINKAGE_MISSING", "所选资源联动规则不存在或已删除。")); continue; }
                    if (!"CUSTOM".equals(rule.kind())) { issues.add(error(node, "LINKAGE_DEFAULT_RULE", "只能引用自定义资源联动规则；默认规则请在联动页面单独维护。")); continue; }
                    boolean hasControllers = hasControllers(rule.config());
                    references.add(new RuleFlowModels.Reference(node.id(), "RESOURCE_LINKAGE", rule.id(), rule.name(), rule.revision(),
                        hasControllers
                            ? "只读引用：该规则已配置真实控制器资源，启用与动作执行仍由资源联动页面单独控制，规则流不会启用它。"
                            : "只读引用：规则流不改写、不启用该联动规则。"));
                    dependencies.add(new RuleFlowModels.Dependency("RESOURCE_LINKAGE", "rule:" + rule.id(), rule.id(), rule.revision()));
                }
                case RuleFlowCatalog.MQTT -> {
                    String applicationId = text(node, node.config().get("applicationId"), 64, "数据输出应用", issues);
                    if (applicationId == null) continue;
                    var rows = jdbc.query("SELECT a.name,(SELECT COUNT(*) FROM application_outputs o WHERE o.application_id=a.id AND o.enabled=TRUE AND o.protocol='MQTT_TLS_3_1_1') FROM output_applications a WHERE a.id=? AND a.tenant_id=? AND a.status='ACTIVE'",
                        (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2)}, applicationId, tenant);
                    if (rows.isEmpty()) { issues.add(error(node, "OUTPUT_APPLICATION_MISSING", "所选数据输出应用不存在、已归档或不属于当前单位。")); continue; }
                    Object[] row = rows.getFirst();
                    if (((Number) row[1]).longValue() < 1) { issues.add(error(node, "OUTPUT_APPLICATION_DISABLED", "所选数据输出应用没有已启用的MQTT输出；HTTPS输出不能作为MQTT引用，规则流也不会代建外发。")); continue; }
                    long revision = jdbc.queryForObject("SELECT revision FROM output_applications WHERE id=? AND tenant_id=?", Long.class, applicationId, tenant);
                    references.add(new RuleFlowModels.Reference(node.id(), "OUTPUT_APPLICATION", applicationId, String.valueOf(row[0]), revision,
                        "只读引用：MQTT外发由已发布应用的数据输出链路承载，规则流不新建、不启停外发。"));
                    dependencies.add(new RuleFlowModels.Dependency("OUTPUT_APPLICATION", "application:" + applicationId, applicationId, revision));
                }
                default -> issues.add(error(node, "NODE_UNKNOWN", "节点类型不在受审目录内：" + node.type()));
            }
        }

        if (!faultNotify && (hasReceiver(receivers.get("FAULT"), "SMS") || hasReceiver(receivers.get("FAULT"), "VOICE")))
            issues.add(new RuleFlowModels.Issue(faultNodeId, "ERROR", "FAULT_NOTIFY_CONTRADICTION",
                "故障处理节点关闭了故障通知，但通道节点仍选择了故障范围；请二选一。"));
        if (Boolean.FALSE.equals(pushMessage) && (hasReceiver(receivers.get("FIRE"), "SMS") || hasReceiver(receivers.get("FAULT"), "SMS")))
            issues.add(new RuleFlowModels.Issue(pushNodeId, "ERROR", "PUSH_MESSAGE_CONTRADICTION",
                "推送方式节点关闭了消息派发，但短信通道仍选择了接收人；请二选一。"));
        if (hasErrors(issues)) return emptyPlan(definition, draftRevision, issues);

        // A narrower-than-unit device scope cannot be honoured by the tenant-level policy consumers.
        if (policyNodes && !"ALL".equals(scopeMode))
            issues.add(new RuleFlowModels.Issue(startNode(definition), "ERROR", "SCOPE_NOT_UNIT_WIDE",
                "通知与报警策略消费者按单位生效，规则流不能声明更窄的设备或位置范围。请将入口范围改为整个单位。"));
        if (hasErrors(issues)) return emptyPlan(definition, draftRevision, issues);

        observeConsumers(tenant, observed, dependencies, issues);
        if (hasErrors(issues)) return emptyPlan(definition, draftRevision, issues);

        // ---- base configuration projection (active=true; the apply step deactivates deterministically) ----
        Map<String, Object> voice = new LinkedHashMap<>(map(baseValue.get("voiceBroadcast")));
        if (definition.nodesOfType(RuleFlowCatalog.EVENT_TYPE).size() == 1) {
            Map<String, Object> fireSubscriptions = new LinkedHashMap<>();
            Map<String, Object> faultSubscriptions = new LinkedHashMap<>();
            if (!faultExcludes.isEmpty())
                for (String model : systemTypes) faultSubscriptions.put(model, List.copyOf(faultExcludes));
            Map<String, Object> subscriptions = new LinkedHashMap<>();
            subscriptions.put("FIRE", fireSubscriptions);
            subscriptions.put("FAULT", faultSubscriptions);
            put(baseValue, "subscriptions", subscriptions, "subscriptions", owned, diffs);
            owned.add("subscriptions");
        }
        if (annotationFields.isEmpty()) annotationFields = RuleFlowCatalog.ANNOTATION_FIELDS;
        boolean smsFire = hasReceiver(receivers.get("FIRE"), "SMS"), voiceFire = hasReceiver(receivers.get("FIRE"), "VOICE");
        boolean smsFault = hasReceiver(receivers.get("FAULT"), "SMS"), voiceFault = hasReceiver(receivers.get("FAULT"), "VOICE");
        if (faultVoice && !voiceFault) voiceFault = true;
        boolean anyChannel = smsFire || voiceFire || smsFault || voiceFault;
        boolean voiceRelevant = definition.nodes().stream().anyMatch(node ->
            node.type().equals(RuleFlowCatalog.VOICE) || node.type().equals(RuleFlowCatalog.FAULT) || node.type().equals(RuleFlowCatalog.ANNOTATE));
        if (voiceRelevant) {
            voice.put("alarmEnabled", voiceFire);
            voice.put("alarmTypes", voiceFire ? List.of("FIRE") : List.of());
            voice.put("alarmFields", List.copyOf(annotationFields));
            voice.put("faultEnabled", voiceFault);
            voice.put("faultTypes", voiceFault ? List.copyOf(faultVoiceTypes) : List.of());
            voice.put("faultFields", List.copyOf(annotationFields));
            voice.put("continuous", Boolean.TRUE.equals(voice.get("continuous")));
            put(baseValue, "voiceBroadcast", voice, "voiceBroadcast", owned, diffs);
            owned.add("voiceBroadcast");
        }
        if (definition.nodesOfType(RuleFlowCatalog.PUSH_TYPE).size() == 1) {
            Map<String, Object> push = new LinkedHashMap<>(map(baseValue.get("pushRules")));
            push.put("periodHours", pushPeriod == null ? 24 : pushPeriod);
            put(baseValue, "pushRules", push, "pushRules", owned, diffs);
            owned.add("pushRules");
        }
        if (definition.nodesOfType(RuleFlowCatalog.FAULT).size() == 1) {
            Map<String, Object> silence = new LinkedHashMap<>(map(baseValue.get("silenceIntervals")));
            silence.put("faultMinutes", faultSilence);
            if (!silence.containsKey("alarmMinutes")) silence.put("alarmMinutes", 0);
            put(baseValue, "silenceIntervals", silence, "silenceIntervals", owned, diffs);
            owned.add("silenceIntervals");
        }
        if (anyChannel) {
            Map<String, Object> unitService = new LinkedHashMap<>();
            unitService.put("receiveAlarm", true);
            unitService.put("pushMessage", pushMessage == null || pushMessage);
            put(baseValue, "unitService", unitService, "unitService", owned, diffs);
            owned.add("unitService");
        }

        // ---- notification rules, one per event scope ----
        for (String scope : List.of("FIRE", "FAULT")) {
            List<Map<String, Object>> contacts = receivers.get(scope);
            if (contacts.isEmpty()) continue;
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (String channel : List.of("SMS", "VOICE")) {
                long interval = escalation.getOrDefault(scope + ":" + channel, 0L);
                List<Map<String, Object>> channelContacts = new ArrayList<>();
                for (Map<String, Object> contact : contacts) {
                    if (!channel.equals(contact.get("channel"))) continue;
                    channelContacts.add(Map.of("contactId", contact.get("contactId"), "level", contact.get("level")));
                }
                if (channelContacts.isEmpty()) continue;
                long maxLevel = channelContacts.stream().mapToLong(item -> ((Number) item.get("level")).longValue()).max().orElse(1);
                grouped.put(channel, channelContacts);
                if (maxLevel == 1 && interval != 0) issues.add(new RuleFlowModels.Issue(startNode(definition), "ERROR", "ESCALATION_UNUSED",
                    channel + " 接收等级只有1级，不能保存无消费者的升级间隔。"));
                escalation.put(scope + ":" + channel, maxLevel == 1 ? 0L : interval);
            }
            if (grouped.isEmpty()) continue;
            // The existing notification rule consumer requires a contiguous SMS receiver set AND a contiguous
            // VOICE receiver set for every event scope. Inventing the missing channel would place calls nobody
            // asked for, so the graph must declare both.
            for (String required : List.of("SMS", "VOICE")) {
                if (grouped.containsKey(required)) continue;
                issues.add(new RuleFlowModels.Issue(startNode(definition), "ERROR", "CHANNEL_INCOMPLETE",
                    scope + " 事件范围缺少" + (required.equals("SMS") ? "短信" : "语音") + "接收人；现有通知规则消费者要求同一事件范围同时具备短信与语音接收人。"
                        + " 请补充" + (required.equals("SMS") ? "短信" : "语音") + "通道节点，或去掉该事件范围。"));
            }
            for (var entry : grouped.entrySet()) {
                List<Long> levels = entry.getValue().stream().map(item -> ((Number) item.get("level")).longValue()).sorted().toList();
                if (levels.getFirst() != 1L || levels.get(levels.size() - 1) != levels.size())
                    issues.add(new RuleFlowModels.Issue(startNode(definition), "ERROR", "LEVEL_GAP",
                        entry.getKey() + " 接收等级必须从1开始连续，不能跳级。"));
            }
            List<RuleFlowModels.ChannelTarget> channelTargets = new ArrayList<>();
            for (var entry : grouped.entrySet())
                channelTargets.add(new RuleFlowModels.ChannelTarget(entry.getKey(), entry.getValue(), escalation.get(scope + ":" + entry.getKey())));
            rules.add(new RuleFlowModels.RuleTarget(scope, List.copyOf(channelTargets)));
            diffRule(observed, scope, channelTargets, diffs);
        }
        if (hasErrors(issues)) return emptyPlan(definition, draftRevision, issues);

        if (mergeTarget != null) diffMerge(observed, mergeTarget, diffs);

        String definitionSha = RuleFlowDefinition.definitionSha256(definition);
        String hash = planHash(definitionSha, draftRevision, dependencies, baseValue, owned, mergeTarget, rules, references);
        return new RuleFlowModels.Plan(definitionSha, draftRevision, true, List.copyOf(issues),
            baseValue, List.copyOf(owned), mergeTarget, List.copyOf(rules), List.copyOf(references),
            List.copyOf(dependencies), List.copyOf(diffs), hash);
    }

    private void observeConsumers(String tenant, Observed observed, List<RuleFlowModels.Dependency> dependencies,
            List<RuleFlowModels.Issue> issues) {
        dependencies.add(new RuleFlowModels.Dependency("ALARM_BASE_CONFIGURATION", "unit", tenant, observed.baseRevision()));
        RuleFlowModels.Binding mergeBinding = observed.bindings().get(bindingKey("ALARM_MERGE_POLICY", "unit"));
        dependencies.add(new RuleFlowModels.Dependency("ALARM_MERGE_POLICY", "unit", tenant, observed.mergeRevision()));
        if (mergeBinding != null && mergeBinding.consumerRevision() != observed.mergeRevision())
            issues.add(new RuleFlowModels.Issue(null, "ERROR", "DEPENDENCY_DRIFT",
                "报警归并策略已在规则流之外被修改（绑定修订 " + mergeBinding.consumerRevision() + "，当前 " + observed.mergeRevision() + "）；请先在联动/报警页面确认，再重新发布。"));
        RuleFlowModels.Binding baseBinding = observed.bindings().get(bindingKey("ALARM_BASE_CONFIGURATION", "unit"));
        if (baseBinding != null && baseBinding.consumerRevision() != observed.baseRevision())
            issues.add(new RuleFlowModels.Issue(null, "ERROR", "DEPENDENCY_DRIFT",
                "报警基础配置已在规则流之外被修改（绑定修订 " + baseBinding.consumerRevision() + "，当前 " + observed.baseRevision() + "）；规则流不会静默覆盖独立编辑。"));
        for (String scope : List.of("FIRE", "FAULT")) {
            RuleFlowModels.Binding binding = observed.bindings().get(bindingKey("NOTIFICATION_RULE", scope));
            if (binding == null) { dependencies.add(new RuleFlowModels.Dependency("NOTIFICATION_RULE", scope, null, null)); continue; }
            var rows = jdbc.query("SELECT revision FROM notification_rules WHERE id=? AND tenant_id=? AND legacy_mode IS NULL AND scope_type='UNIT'",
                (rs, n) -> rs.getLong(1), binding.consumerId(), tenant);
            if (rows.isEmpty()) {
                issues.add(new RuleFlowModels.Issue(null, "ERROR", "DEPENDENCY_MISSING", "规则流绑定的" + scope + "通知规则已不存在；请重新发布以重建消费者。"));
                continue;
            }
            if (rows.getFirst() != binding.consumerRevision())
                issues.add(new RuleFlowModels.Issue(null, "ERROR", "DEPENDENCY_DRIFT",
                    scope + " 通知规则已在规则流之外被修改（绑定修订 " + binding.consumerRevision() + "，当前 " + rows.getFirst() + "）；规则流不会静默覆盖。"));
            dependencies.add(new RuleFlowModels.Dependency("NOTIFICATION_RULE", scope, binding.consumerId(), rows.getFirst()));
        }
    }

    private void validateGraph(RuleFlowModels.Definition definition, List<RuleFlowModels.Issue> issues) {
        Map<String, Integer> counts = new HashMap<>();
        for (RuleFlowModels.Node node : definition.nodes()) counts.merge(node.type(), 1, Integer::sum);
        for (var entry : counts.entrySet()) {
            RuleFlowModels.NodeType type = RuleFlowCatalog.type(entry.getKey());
            if (type == null) continue;
            if (entry.getValue() < type.minCount()) issues.add(new RuleFlowModels.Issue(firstOfType(definition, entry.getKey()), "ERROR", "NODE_COUNT", type.label() + " 必须至少出现 " + type.minCount() + " 次。"));
            if (entry.getValue() > type.maxCount()) issues.add(new RuleFlowModels.Issue(firstOfType(definition, entry.getKey()), "ERROR", "NODE_COUNT", type.label() + " 最多出现 " + type.maxCount() + " 次。"));
        }
        for (String required : RuleFlowCatalog.NATIVE_STAGES)
            if (counts.getOrDefault(required, 0) != 1) issues.add(new RuleFlowModels.Issue(firstOfType(definition, required), "ERROR", "NATIVE_STAGE",
                "原生阶段 " + required + " 必须存在且唯一；规则流不能关闭或重排来源、持久化与报警创建。"));
        if (hasErrors(issues)) return;
        if (hasCycle(definition)) { issues.add(new RuleFlowModels.Issue(null, "ERROR", "GRAPH_CYCLE", "规则流不能包含环路。")); return; }
        String start = firstOfType(definition, RuleFlowCatalog.DEVICE_MESSAGE);
        for (RuleFlowModels.Node node : definition.nodes())
            if (!node.id().equals(start) && !reachable(definition, start, node.id()))
                issues.add(new RuleFlowModels.Issue(node.id(), "ERROR", "GRAPH_UNREACHABLE", "节点没有从设备消息节点可达的路径。"));
        if (!reachable(definition, start, firstOfType(definition, RuleFlowCatalog.PERSIST)))
            issues.add(new RuleFlowModels.Issue(start, "ERROR", "NATIVE_STAGE", "持久化阶段必须位于设备消息之后。"));
        String persist = firstOfType(definition, RuleFlowCatalog.PERSIST);
        if (!reachable(definition, persist, firstOfType(definition, RuleFlowCatalog.CREATE_ALARM)))
            issues.add(new RuleFlowModels.Issue(persist, "ERROR", "NATIVE_STAGE", "创建报警阶段必须位于持久化之后。"));
        if (depth(definition) > RuleFlowDefinition.MAX_DEPTH)
            issues.add(new RuleFlowModels.Issue(start, "ERROR", "GRAPH_DEPTH", "规则流层级超过上限 " + RuleFlowDefinition.MAX_DEPTH + "。"));
    }

    private record Scope(String mode) { }

    private Scope scope(AuthService.Identity actor, String tenant, RuleFlowModels.Node node, boolean policyNodes,
            List<RuleFlowModels.Issue> issues) {
        Object raw = node.config().get("scope");
        if (raw == null) return new Scope("ALL");
        if (!(raw instanceof Map<?, ?> source)) { issues.add(error(node, "SCOPE_SHAPE", "入口范围必须是对象。")); return null; }
        Map<String, Object> scope = map(source);
        Set<String> unknown = new LinkedHashSet<>(scope.keySet());
        unknown.removeAll(SCOPE_KEYS);
        if (!unknown.isEmpty()) { issues.add(error(node, "SCOPE_SHAPE", "入口范围包含不支持的字段：" + String.join(",", unknown))); return null; }
        String mode = text(node, scope.get("mode"), 16, "范围模式", issues);
        if (mode == null) return null;
        if (!Set.of("ALL", "PLACES", "DEVICES").contains(mode)) { issues.add(error(node, "SCOPE_MODE", "范围模式无效。")); return null; }
        List<String> placeIds = stringList(node, scope.get("placeIds"), 50, 64, "位置列表", issues);
        List<String> deviceIds = stringList(node, scope.get("deviceIds"), 200, 64, "设备列表", issues);
        if ("ALL".equals(mode) && (!placeIds.isEmpty() || !deviceIds.isEmpty())) issues.add(error(node, "SCOPE_MODE", "整个单位模式不能再指定位置或设备。"));
        if ("PLACES".equals(mode) && (placeIds.isEmpty() || !deviceIds.isEmpty())) issues.add(error(node, "SCOPE_MODE", "位置范围必须且只能选择位置。"));
        if ("DEVICES".equals(mode) && (deviceIds.isEmpty() || !placeIds.isEmpty())) issues.add(error(node, "SCOPE_MODE", "设备范围必须且只能选择设备。"));
        for (String placeId : placeIds)
            if (jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=? AND tenant_id=?", Long.class, placeId, tenant) == 0)
                issues.add(error(node, "SCOPE_PLACE", "位置不属于当前单位：" + placeId));
        for (String deviceId : deviceIds) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE id=? AND tenant_id=?", Long.class, deviceId, tenant) == 0) {
                issues.add(error(node, "SCOPE_DEVICE", "设备不属于当前单位：" + deviceId));
                continue;
            }
            if (!devices.contains(actor.user().id(), tenant, deviceId))
                issues.add(error(node, "SCOPE_NOT_VISIBLE", "设备不在当前账户的授权设备范围内：" + deviceId));
        }
        if ("ALL".equals(mode) && policyNodes && !devices.all(actor.user().id(), tenant))
            issues.add(error(node, "SCOPE_NOT_UNIT_WIDE", "通知与报警策略按整个单位生效，需要当前账户明确拥有本单位全部设备范围（ALL_TENANT）；只读引用规则流不受此限制。"));
        return new Scope(mode);
    }

    private List<Map<String, Object>> receivers(RuleFlowModels.Node node, String tenant, String channel, List<RuleFlowModels.Issue> issues) {
        Object raw = node.config().get("receivers");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> items)) { issues.add(error(node, "RECEIVERS_SHAPE", "接收人必须是列表。")); return List.of(); }
        if (items.size() > 50) { issues.add(error(node, "RECEIVERS_SHAPE", "接收人数量超过上限。")); return List.of(); }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<Long> levels = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) { issues.add(error(node, "RECEIVERS_SHAPE", "接收人结构无效。")); continue; }
            Map<String, Object> receiver = map(item);
            Set<String> unknown = new LinkedHashSet<>(receiver.keySet());
            unknown.removeAll(RECEIVER_KEYS);
            if (!unknown.isEmpty()) { issues.add(error(node, "RECEIVERS_SHAPE", "接收人包含不支持的字段：" + String.join(",", unknown))); continue; }
            String contactId = text(node, receiver.get("contactId"), 64, "联系人", issues);
            Integer level = integer(node, receiver.get("level"), 1, 5, "接收等级", issues);
            if (contactId == null || level == null) continue;
            if (!seen.add(contactId)) { issues.add(error(node, "RECEIVER_DUPLICATE", "同一通道的联系人不能重复。")); continue; }
            var rows = jdbc.query("SELECT enabled FROM notification_contacts WHERE id=? AND tenant_id=?", (rs, n) -> rs.getBoolean(1), contactId, tenant);
            if (rows.isEmpty()) { issues.add(error(node, "CONTACT_MISSING", "联系人不存在或不属于当前单位：" + contactId)); continue; }
            if (!rows.getFirst()) { issues.add(error(node, "CONTACT_DISABLED", "联系人已停用：" + contactId)); continue; }
            levels.add(level.longValue());
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("contactId", contactId);
            encoded.put("level", level);
            encoded.put("channel", channel);
            result.add(encoded);
        }
        return result;
    }

    private Map<String, Object> section(RuleFlowModels.Node node, String key, Set<String> allowed, List<RuleFlowModels.Issue> issues) {
        Object raw = node.config().get(key);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> source)) { issues.add(error(node, key.toUpperCase() + "_SHAPE", "配置段必须是对象。")); return null; }
        Map<String, Object> section = map(source);
        Set<String> unknown = new LinkedHashSet<>(section.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) { issues.add(error(node, key.toUpperCase() + "_SHAPE", "配置段包含不支持的字段：" + String.join(",", unknown))); return null; }
        return section;
    }

    private static boolean hasControllers(Map<String, Object> config) {
        Object targets = config.get("targets");
        if (!(targets instanceof Map<?, ?> map)) return false;
        Object ids = map.get("controllerIds");
        return ids instanceof List<?> list && !list.isEmpty();
    }

    private void put(Map<String, Object> base, String key, Map<String, Object> next, String label,
            List<String> owned, List<RuleFlowModels.Diff> diffs) {
        if (owned.contains(label)) return;
        Object before = base.get(key);
        String beforeText = RuleFlowDefinition.canonical(before);
        String afterText = RuleFlowDefinition.canonical(next);
        if (!beforeText.equals(afterText)) diffs.add(new RuleFlowModels.Diff("ALARM_BASE_CONFIGURATION", "unit", key, beforeText, afterText));
        base.put(key, next);
    }

    private void diffMerge(Observed observed, RuleFlowModels.MergeTarget target, List<RuleFlowModels.Diff> diffs) {
        String before = observed.mergeEnabled() + "/" + observed.mergeWindow();
        String after = target.enabled() + "/" + target.windowSeconds();
        if (!before.equals(after)) diffs.add(new RuleFlowModels.Diff("ALARM_MERGE_POLICY", "unit", "enabled+window", before, after));
    }

    private void diffRule(Observed observed, String scope, List<RuleFlowModels.ChannelTarget> channels, List<RuleFlowModels.Diff> diffs) {
        RuleFlowModels.Binding binding = observed.bindings().get(bindingKey("NOTIFICATION_RULE", scope));
        String before = binding == null ? "（不存在）" : "修订 " + binding.consumerRevision();
        String after = channels.size() + " 个通道 / " + channels.stream().mapToInt(item -> item.receivers().size()).sum() + " 名接收人";
        diffs.add(new RuleFlowModels.Diff("NOTIFICATION_RULE", scope, "receivers", before, after));
    }

    private RuleFlowModels.Plan emptyPlan(RuleFlowModels.Definition definition, long draftRevision, List<RuleFlowModels.Issue> issues) {
        String sha = RuleFlowDefinition.definitionSha256(definition);
        return new RuleFlowModels.Plan(sha, draftRevision, false, List.copyOf(issues), Map.of(), List.of(), null, List.of(),
            List.of(), List.of(), List.of(), Digests.sha256(sha + "|" + draftRevision + "|invalid"));
    }

    private static String planHash(String definitionSha, long draftRevision, List<RuleFlowModels.Dependency> dependencies,
            Map<String, Object> baseValue, List<String> owned, RuleFlowModels.MergeTarget merge,
            List<RuleFlowModels.RuleTarget> rules, List<RuleFlowModels.Reference> references) {
        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("definition", definitionSha);
        hashInput.put("draft_revision", draftRevision);
        hashInput.put("dependencies", dependencies);
        hashInput.put("base", baseValue);
        hashInput.put("owned", owned);
        hashInput.put("merge", merge);
        hashInput.put("rules", rules);
        hashInput.put("references", references);
        return Digests.sha256(RuleFlowDefinition.canonical(hashInput));
    }

    // ---- graph helpers ----

    private static boolean hasErrors(List<RuleFlowModels.Issue> issues) {
        return issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity()));
    }

    private static String startNode(RuleFlowModels.Definition definition) {
        return firstOfType(definition, RuleFlowCatalog.DEVICE_MESSAGE);
    }

    private static String firstOfType(RuleFlowModels.Definition definition, String type) {
        for (RuleFlowModels.Node node : definition.nodes()) if (node.type().equals(type)) return node.id();
        return null;
    }

    private static boolean reachable(RuleFlowModels.Definition definition, String from, String to) {
        if (from == null || to == null) return false;
        if (from.equals(to)) return true;
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (RuleFlowModels.Edge edge : definition.edges()) {
                if (!edge.from().equals(current)) continue;
                if (edge.to().equals(to)) return true;
                if (seen.add(edge.to())) queue.add(edge.to());
            }
        }
        return false;
    }

    private static boolean hasCycle(RuleFlowModels.Definition definition) {
        Map<String, Integer> incoming = new HashMap<>();
        for (RuleFlowModels.Node node : definition.nodes()) incoming.put(node.id(), 0);
        for (RuleFlowModels.Edge edge : definition.edges()) incoming.merge(edge.to(), 1, Integer::sum);
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (var entry : incoming.entrySet()) if (entry.getValue() == 0) queue.add(entry.getKey());
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (RuleFlowModels.Edge edge : definition.edges()) {
                if (!edge.from().equals(current)) continue;
                if (incoming.merge(edge.to(), -1, Integer::sum) == 0) queue.add(edge.to());
            }
        }
        return visited != definition.nodes().size();
    }

    private static int depth(RuleFlowModels.Definition definition) {
        Map<String, Integer> level = new HashMap<>();
        for (RuleFlowModels.Node node : definition.nodes()) level.put(node.id(), 0);
        for (int pass = 0; pass < definition.nodes().size(); pass++) {
            boolean changed = false;
            for (RuleFlowModels.Edge edge : definition.edges()) {
                int candidate = level.getOrDefault(edge.from(), 0) + 1;
                if (candidate > level.getOrDefault(edge.to(), 0)) { level.put(edge.to(), candidate); changed = true; }
            }
            if (!changed) break;
        }
        return level.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    // ---- typed config helpers ----

    private static RuleFlowModels.Issue error(RuleFlowModels.Node node, String code, String message) {
        return new RuleFlowModels.Issue(node.id(), "ERROR", code, message);
    }

    private static Boolean bool(RuleFlowModels.Node node, Object value, String label, List<RuleFlowModels.Issue> issues) {
        if (value == null) { issues.add(error(node, "CONFIG_REQUIRED", "必须明确填写" + label + "。")); return null; }
        if (!(value instanceof Boolean flag)) { issues.add(error(node, "CONFIG_TYPE", label + "必须是布尔值。")); return null; }
        return flag;
    }

    private static Integer integer(RuleFlowModels.Node node, Object value, int min, int max, String label, List<RuleFlowModels.Issue> issues) {
        if (value == null) return null;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) { issues.add(error(node, "CONFIG_TYPE", label + "必须是整数。")); return null; }
        int parsed = number.intValue();
        if (parsed < min || parsed > max) { issues.add(error(node, "CONFIG_RANGE", label + "必须在" + min + "至" + max + "之间。")); return null; }
        return parsed;
    }

    private static String text(RuleFlowModels.Node node, Object value, int max, String label, List<RuleFlowModels.Issue> issues) {
        if (value == null) { issues.add(error(node, "CONFIG_REQUIRED", "必须选择" + label + "。")); return null; }
        if (!(value instanceof String string) || string.isBlank() || string.length() > max) { issues.add(error(node, "CONFIG_TYPE", label + "无效。")); return null; }
        return string;
    }

    private static List<String> stringList(RuleFlowModels.Node node, Object value, int maxItems, int maxLength,
            String label, List<RuleFlowModels.Issue> issues) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> items)) { issues.add(error(node, "CONFIG_TYPE", label + "必须是列表。")); return List.of(); }
        if (items.size() > maxItems) { issues.add(error(node, "CONFIG_RANGE", label + "数量超过上限。")); return List.of(); }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String string) || string.isBlank() || string.length() > maxLength) {
                issues.add(error(node, "CONFIG_TYPE", label + "包含无效条目。"));
                continue;
            }
            if (!result.contains(string)) result.add(string);
        }
        return result;
    }

    private static boolean hasReceiver(List<Map<String, Object>> receivers, String channel) {
        return receivers != null && receivers.stream().anyMatch(item -> channel.equals(item.get("channel")));
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) for (var entry : source.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
}
