package com.fireplatform;

import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit broad scopes preserve legacy scenarios; N04 least-privilege scenarios do not call this fixture. */
public final class N04TestScopes {
    private N04TestScopes() { }

    public static void clear(JdbcTemplate jdbc) {
        for (String table : new String[]{"user_device_scope_places", "user_device_scope_devices", "user_device_scopes", "access_user_roles", "access_role_permissions", "access_roles"})
            jdbc.update("DELETE FROM " + table);
    }

    public static void grantExisting(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) SELECT u.id,u.tenant_id,'ALL_TENANT',1 FROM app_users u WHERE u.user_type='CUSTOMER' AND NOT EXISTS(SELECT 1 FROM user_device_scopes s WHERE s.user_id=u.id AND s.tenant_id=u.tenant_id)");
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) SELECT g.user_id,g.tenant_id,'ALL_TENANT',1 FROM user_tenant_grants g JOIN app_users u ON u.id=g.user_id WHERE u.user_type='PLATFORM' AND NOT EXISTS(SELECT 1 FROM user_device_scopes s WHERE s.user_id=g.user_id AND s.tenant_id=g.tenant_id)");
    }
}
