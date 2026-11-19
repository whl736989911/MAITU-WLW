package com.fireplatform.device;

/** Shared read-only projection of legacy normalized events and authenticated generic captures. */
public final class DeviceEventProjection {
    private DeviceEventProjection() { }

    public static final String SQL = "(SELECT e.id,e.tenant_id,e.device_id,e.kind,e.method,e.observed_at,e.created_at,"
        + "COALESCE(m.received_at,rm.received_at) AS received_at,"
        + "COALESCE(m.vendor_platform,e.source_platform,'LEGACY') AS source_kind,"
        + "COALESCE(m.state,rm.application_state) AS status,e.params_json AS params_json"
        + " FROM legacy_ingest_events e"
        + " LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id"
        + " LEFT JOIN raw_messages rm ON rm.id=e.raw_message_id AND rm.tenant_id=e.tenant_id"
        + " UNION ALL SELECT 'GENERIC_MESSAGE:'||r.id AS id,r.tenant_id,r.device_id,'OTHER' AS kind,s.protocol AS method,"
        + "r.occurred_at AS observed_at,r.received_at AS created_at,r.received_at,s.protocol AS source_kind,"
        + "r.application_state AS status,NULL AS params_json FROM raw_messages r"
        + " JOIN ingestion_sources s ON s.id=r.source_id AND s.tenant_id=r.tenant_id"
        + " WHERE r.capture_provenance='AUTHENTICATED_SOURCE' AND r.device_id IS NOT NULL"
        + " AND NOT EXISTS(SELECT 1 FROM legacy_ingest_events be WHERE be.raw_message_id=r.id AND be.tenant_id=r.tenant_id))";
}
