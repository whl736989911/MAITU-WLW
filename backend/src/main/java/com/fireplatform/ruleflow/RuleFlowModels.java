package com.fireplatform.ruleflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Read/write projections for tenant rule flows. Nothing here is a second runtime: it describes real consumer writes. */
public final class RuleFlowModels {
    private RuleFlowModels() { }

    /** One reviewed graph node. Config stays an untyped map only in transport; the compiler validates closed key sets. */
    public record Node(String id, String type, Map<String, Object> config, int x, int y) { }

    public record Edge(String from, String to) { }

    /** A structurally parsed graph. Semantics are not validated here so a draft can be saved while incomplete. */
    public record Definition(List<Node> nodes, List<Edge> edges) {
        public Definition {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        public Node node(String id) {
            for (Node node : nodes) if (node.id().equals(id)) return node;
            return null;
        }

        public List<Node> nodesOfType(String type) {
            return nodes.stream().filter(node -> node.type().equals(type)).toList();
        }
    }

    /** Per-node compile outcome. Errors stop a publish; notes do not. */
    public record Issue(String nodeId, String severity, String code, String message) { }

    /** Exact consumer revision observed at preview time; publish must still observe it or nothing is written. */
    public record Dependency(String consumerKind, String scopeKey, String consumerId, Long revision) { }

    public record Diff(String consumerKind, String scopeKey, String field, String before, String after) { }

    /** Receivers grouped per channel for one notification event scope. */
    public record ChannelTarget(String channel, List<Map<String, Object>> receivers, Long escalationMinutes) { }

    public record RuleTarget(String eventScope, List<ChannelTarget> channels) { }

    public record MergeTarget(boolean enabled, Integer windowSeconds) { }

    /** A verified reference to an existing consumer that a flow never rewrites. */
    public record Reference(String nodeId, String consumerKind, String consumerId, String consumerName, long consumerRevision, String effect) { }

    /**
     * Immutable compile plan. Hashed over the definition, the exact dependency revisions and the resolved
     * consumer targets, so a preview is a real dependency CAS rather than a cosmetic confirmation.
     */
    public record Plan(String definitionSha256, long draftRevision, boolean ok, List<Issue> issues,
                       Map<String, Object> baseConfiguration, List<String> ownedFields,
                       MergeTarget merge, List<RuleTarget> notificationRules,
                       List<Reference> references, List<Dependency> dependencies, List<Diff> diffs,
                       String planHash) { }

    public record Flow(String id, String tenantId, String name, String description, String status, long revision,
                       Long publishedRevision, boolean enabled, Boolean lastCompileOk, List<Issue> lastCompileIssues,
                       OffsetDateTime lastCompileAt, String createdBy, OffsetDateTime createdAt,
                       String updatedBy, OffsetDateTime updatedAt, OffsetDateTime archivedAt, String archivedReason,
                       String definitionSha256) { }

    /** A published flow whose current draft no longer matches the live definition. */
    public record FlowDetail(Flow flow, Definition definition, Long publishedDraftDrift) { }

    public record Revision(long revision, String definitionSha256, String planHash, String reason,
                           String publishedBy, OffsetDateTime publishedAt) { }

    public record RevisionDetail(long revision, Definition definition, Plan plan, String reason,
                                 String publishedBy, OffsetDateTime publishedAt) { }

    public record Binding(String consumerKind, String scopeKey, String consumerId, long consumerRevision,
                          List<String> ownedFields, OffsetDateTime updatedAt) { }

    public record CompileReport(String id, long draftRevision, String planHash, boolean ok, List<Issue> issues,
                                String createdBy, OffsetDateTime createdAt) { }

    public record Event(String id, String action, Long revisionBefore, long revisionAfter, String actorId,
                        String reason, String detailJson, OffsetDateTime createdAt) { }

    /** Live readback of the consumers a flow currently owns, so the UI never guesses. */
    public record ConsumerReadback(String consumerKind, String scopeKey, String consumerId, long consumerRevision,
                                   boolean driftDetected, String effect, Map<String, Object> detail) { }

    public record CompiledView(String flowId, long publishedRevision, boolean enabled, List<ConsumerReadback> consumers) { }

    public record NodeType(String type, String label, String category, String port, boolean fixed, int minCount,
                           int maxCount, boolean supported, String rationale, String description) { }

    public record ChannelSupport(String channel, boolean supported, String rationale) { }

    public record Catalog(List<NodeType> nodeTypes, List<ChannelSupport> channels, List<Option> systemTypes,
                          List<PlaceOption> places, List<DeviceOption> devices, List<ContactOption> contacts,
                          List<LinkageOption> linkageRules, List<ApplicationOption> applications,
                          List<String> eventCodes, List<String> linkageActions, boolean channelSupportKnown) { }

    public record Option(String id, String name) { }

    public record PlaceOption(String id, String name, String path) { }

    public record DeviceOption(String id, String name, String model, String placePath, boolean inCurrentScope) { }

    public record ContactOption(String id, String name, String phoneMasked, boolean enabled) { }

    public record LinkageOption(String id, String name, String kind, boolean enabled, boolean hasControllers) { }

    public record ApplicationOption(String id, String name, String status, int enabledOutputs,
                                    int mqttEnabledOutputs) { }
}
