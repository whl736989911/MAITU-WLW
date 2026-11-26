package com.fireplatform.device;

/** Asset display only. Source matching and command applicability use the original model code. */
public final class DeviceModelDisplay {
    private DeviceModelDisplay() { }

    public static final String SQL = """
        (CASE WHEN d.model='800235' AND EXISTS (
            SELECT 1 FROM device_source_bindings b
            WHERE b.tenant_id=d.tenant_id AND b.device_id=d.id
              AND b.driver_key='HIKVISION_FIRECLOUD' AND b.state='ACTIVE'
        ) THEN 'HF-FY500-G' ELSE d.model END)
        """;
}
