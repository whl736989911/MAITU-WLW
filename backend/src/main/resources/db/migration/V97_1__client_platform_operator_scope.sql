-- A protected client-only operator is provisioned explicitly, never during migration.
-- Read projections preserve physical grants and add only the reserved operator's dynamic scope.
CREATE VIEW effective_user_tenant_grants AS
    SELECT user_id, tenant_id FROM user_tenant_grants
      WHERE user_id <> 'client-platform-operator'
    UNION ALL
    SELECT u.id, t.id FROM app_users u CROSS JOIN tenants t
      WHERE u.id = 'client-platform-operator' AND u.user_type = 'PLATFORM'
        AND u.is_protected = TRUE AND u.enabled = TRUE AND t.enabled = TRUE;

CREATE VIEW effective_user_device_scopes AS
    SELECT user_id, tenant_id, mode, revision FROM user_device_scopes
      WHERE user_id <> 'client-platform-operator'
    UNION ALL
    SELECT u.id, t.id, 'ALL_TENANT', CAST(1 AS BIGINT) FROM app_users u CROSS JOIN tenants t
      WHERE u.id = 'client-platform-operator' AND u.user_type = 'PLATFORM'
        AND u.is_protected = TRUE AND u.enabled = TRUE AND t.enabled = TRUE;
