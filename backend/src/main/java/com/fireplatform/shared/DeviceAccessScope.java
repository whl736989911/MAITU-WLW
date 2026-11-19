package com.fireplatform.shared;

import java.util.List;

/** Identity owns the policy; device-owning domains still verify existence and tenant ownership. */
public interface DeviceAccessScope {
    /** SQL references must be qualified, trusted outer columns, never request fields. Bindings follow existing SQL parameters. */
    record Filter(String sql, List<Object> bindings) {
        public String and() { return " AND (" + sql + ")"; }
    }

    Filter predicate(String actorId, String tenantColumn, String deviceColumn);
    Filter allPredicate(String actorId, String tenantColumn);
    /** Place-level target filter: ALL_TENANT, or a PLACE_SUBTREE containing the place or one of its ancestors. */
    Filter placePredicate(String actorId, String tenantColumn, String placeColumn);
    boolean contains(String actorId, String tenantId, String deviceId);
    boolean all(String actorId, String tenantId);
    boolean coversPlace(String actorId, String tenantId, String placeId);
    /** Bulk membership check for scoped candidate lists; implementations must not issue one query per user. */
    java.util.Set<String> usersWithDevice(java.util.List<String> userIds, String tenantId, String deviceId);
    /** Canonical current scope content, binding aggregate receipts to the devices originally visible. */
    String fingerprint(String actorId, String tenantId);

    default void requireDevice(String actorId, String tenantId, String deviceId) {
        if (!contains(actorId, tenantId, deviceId)) throw ApiError.missing();
    }

    default void requireAll(String actorId, String tenantId) {
        if (!all(actorId, tenantId)) throw ApiError.forbidden("当前操作需要明确的全单位设备范围");
    }

    /** Place targets require the explicit unit or a dynamic subtree covering that place. */
    default void requirePlace(String actorId, String tenantId, String placeId) {
        if (!coversPlace(actorId, tenantId, placeId)) throw ApiError.missing();
    }
}
