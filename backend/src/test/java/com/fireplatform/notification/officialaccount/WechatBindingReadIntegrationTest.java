package com.fireplatform.notification.officialaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Offline, isolated metadata only. No OAuth, provider connection, valid OpenID or sending credential is used. */
@SpringBootTest(properties = {
    "app.roles=api", "app.storage=h2-dev", "app.task-poll-ms=60000",
    "spring.datasource.url=jdbc:h2:mem:wechat-binding-read-regression;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WechatBindingReadIntegrationTest {
    private static final String PASSWORD = "Wechat-Read-Regression-Confirmation-2026";
    private static final String APP_ID = "wx0000000000000011";
    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry registry) {
        registry.add("APP_BOOTSTRAP_USERNAME", () -> "admin");
        registry.add("APP_BOOTSTRAP_PASSWORD", () -> "Initial-Wechat-Read-Regression-2026");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    String encodedPassword;

    @Test
    void namedHistoryAndCurrentAccountRecipientsKeepUserAndTenantBoundaries() throws Exception {
        encodedPassword = passwords.encode(PASSWORD);
        jdbc.update("UPDATE app_users SET display_name=?,password_hash=?,must_change_password=FALSE,enabled=TRUE WHERE id='bootstrap-admin'",
            "阅读回归管理员", encodedPassword);
        String tenant = UUID.randomUUID().toString(), otherTenant = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants(id,name) VALUES(?,?),(?,?)", tenant, "Isolated recipient A", otherTenant, "Isolated recipient B");
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES('bootstrap-admin',?)", tenant);
        for (String permission : List.of("inbox:read", "notification-config:read"))
            jdbc.update("INSERT INTO user_permissions(user_id,permission) SELECT 'bootstrap-admin',? WHERE NOT EXISTS"
                + " (SELECT 1 FROM user_permissions WHERE user_id='bootstrap-admin' AND permission=?)", permission, permission);
        // A configured identity, but the channel stays disabled and has no provider secrets.
        jdbc.update("UPDATE notification_wechat_config SET app_id=?,enabled=FALSE WHERE id=1", APP_ID);
        String activeUser = user(tenant, "A 可用接收者", true);
        String unsubscribedUser = user(tenant, "B 已退订接收者", true);
        String disabledUser = user(tenant, "C 已停用账户", false);
        String foreignUser = user(otherTenant, "D 其他单位", true);
        String active = binding(tenant, activeUser, APP_ID, "ACTIVE", "SUBSCRIBED");
        String unsubscribed = binding(tenant, unsubscribedUser, APP_ID, "ACTIVE", "UNSUBSCRIBED");
        binding(tenant, disabledUser, APP_ID, "ACTIVE", "SUBSCRIBED");
        binding(otherTenant, foreignUser, APP_ID, "ACTIVE", "SUBSCRIBED");
        binding(tenant, activeUser, "wx0000000000000022", "ACTIVE", "SUBSCRIBED");
        binding(tenant, activeUser, APP_ID, "REVOKED", "SUBSCRIBED");
        String ownBinding = binding(null, "bootstrap-admin", APP_ID, "ACTIVE", "SUBSCRIBED");
        String ownHistory = history(ownBinding, "bootstrap-admin");
        history(active, activeUser);

        String token = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("username", "admin", "password", PASSWORD, "audience", "management")))))
            .get("access_token").asText();
        JsonNode history = body(mvc.perform(get("/api/management/me/wechat-binding/history?page=1&size=20")
            .header("Authorization", "Bearer " + token)));
        assertThat(history.get("total").asLong()).isEqualTo(1);
        assertThat(history.get("items").get(0).get("id").asText()).isEqualTo(ownHistory);
        assertThat(history.get("items").get(0).get("actor_name").asText()).isEqualTo("阅读回归管理员");

        JsonNode recipients = body(mvc.perform(get("/api/management/notification-wechat/recipients")
            .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)));
        List<String> ids = new ArrayList<>();
        for (JsonNode recipient : recipients) {
            ids.add(recipient.get("binding_id").asText());
            if (recipient.get("binding_id").asText().equals(active)) {
                assertThat(recipient.get("user_name").asText()).isEqualTo("A 可用接收者");
                assertThat(recipient.get("usable").asBoolean()).isTrue();
            } else if (recipient.get("binding_id").asText().equals(unsubscribed)) {
                assertThat(recipient.get("user_name").asText()).isEqualTo("B 已退订接收者");
                assertThat(recipient.get("usable").asBoolean()).isFalse();
            }
        }
        assertThat(ids).containsExactlyInAnyOrder(active, unsubscribed);
    }

    private String user(String tenant, String name, boolean enabled) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected)"
            + " VALUES(?,?,?,'CUSTOMER',?,?,FALSE,?,FALSE)", id, "qa-wx-" + id, name, tenant, encodedPassword, enabled);
        return id;
    }
    private String binding(String tenant, String user, String appId, String state, String follow) {
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO notification_wechat_bindings(id,tenant_id,user_id,app_id,openid_cipher,openid_nonce,openid_masked,"
            + "openid_fingerprint,status,active_slot,follow_state,revision,bound_at,last_seen_at,revoked_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)",
            id, tenant, user, appId, "isolated-not-a-provider-cipher", "isolated-nonce", "isolated***", id,
            state, state.equals("ACTIVE") ? "ACTIVE" : null, follow, now, now, state.equals("REVOKED") ? now : null);
        return id;
    }
    private String history(String binding, String user) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notification_wechat_binding_history(id,binding_id,user_id,event,masked_openid,actor_id,created_at)"
            + " VALUES(?,?,?,'BOUND','isolated***','bootstrap-admin',?)", id, binding, user, OffsetDateTime.now());
        return id;
    }
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
