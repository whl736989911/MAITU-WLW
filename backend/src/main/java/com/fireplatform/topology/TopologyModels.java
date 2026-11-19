package com.fireplatform.topology;

import java.time.OffsetDateTime;
import java.util.List;

/** Read projections for real gateway/device/measurement links. Nothing here is inferred from names or models. */
public final class TopologyModels {
    private TopologyModels() { }
    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public record Gateway(String id, String tenantId, String address, String name, String protocol,
                          String deviceId, String deviceName, String state, long revision,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt, long visibleChildren) { }

    public record Link(String id, String tenantId, String gatewayId, String gatewayAddress, String gatewayName,
                       String childKind, String deviceId, String deviceName, String deviceEui, String placeId,
                       String placeName, String channelId, String channelName, String channelType,
                       boolean channelEnabled, OffsetDateTime deviceArchivedAt, String provenance,
                       String observationSource, String observationEventId, OffsetDateTime observedAt,
                       boolean verified, String verifiedBy, OffsetDateTime verifiedAt, String verificationNote,
                       String state, String revokedBy, OffsetDateTime revokedAt, String revokeReason,
                       long revision, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record GatewayPage(List<Gateway> items, long total, int page, int size, boolean scope_limited) { }

    public record LinkPage(List<Link> items, long total, int page, int size, boolean scope_limited) { }

    /** Children are bounded; partial device scope only ever returns visible children. */
    public record GatewayTree(Gateway gateway, List<Link> links, boolean scope_limited, boolean truncated,
                              String note) { }

    public record IdResult(String id) { }
}
