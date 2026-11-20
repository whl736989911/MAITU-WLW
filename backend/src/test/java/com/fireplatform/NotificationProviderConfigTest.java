package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.transport.AliyunNotificationClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
    "app.roles=all", "app.storage=h2-dev", "fire.notification.real-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:notification-provider-config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "APP_BOOTSTRAP_USERNAME=admin", "APP_BOOTSTRAP_PASSWORD=Initial-provider-config-fixture",
    "app.notification-provider-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationProviderConfigTest {
    private static final String PATH = "/api/management/notification-provider";
    private static final String INITIAL = "Initial-provider-config-fixture";
    private static final String PASSWORD = "Changed-provider-config-fixture";

    private enum Channel {
        SMS("sms", "sms_template_code", "name", "device_name"),
        VOICE("voice", "tts_code", "site", "address");

        final String id;
        final String enabled;
        final String mapping;
        final String confirmed;
        final String variable;
        final String field;

        Channel(String prefix, String id, String variable, String field) {
            this.id = id;
            this.enabled = prefix + "_enabled";
            this.mapping = prefix + "_template_variables";
            this.confirmed = mapping + "_confirmed";
            this.variable = variable;
            this.field = field;
        }

        String replacementId() { return name() + "_REPLACEMENT"; }
        Channel other() { return this == SMS ? VOICE : SMS; }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AliyunNotificationClient notificationClient;
    @Autowired JdbcTemplate jdbc;
    private String token;

    @BeforeAll void signIn() throws Exception {
        token = login(INITIAL);
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(json.writeValueAsBytes(Map.of("current_password", INITIAL, "new_password", PASSWORD))))
            .andExpect(status().isNoContent());
        token = login(PASSWORD);
    }

    @BeforeEach void seedExistingDisabledChannels() {
        jdbc.update("""
            UPDATE notification_provider_config SET sms_enabled=FALSE,voice_enabled=FALSE,
              sms_template_code='SMS_ORIGINAL',tts_code='VOICE_ORIGINAL',
              sms_template_variables='{"name":"device_name"}',voice_template_variables='{"site":"address"}'
            WHERE id=1
            """);
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void requiresFreshConfirmationEvenForIdenticalMapping(Channel channel) throws Exception {
        var before = read();
        var input = request(before).put(channel.id, channel.replacementId());
        map(input, channel);
        save(input).andExpect(status().isBadRequest());
        input.put(channel.other().confirmed, true);
        save(input).andExpect(status().isBadRequest());
        assertThat(read()).isEqualTo(before);

        input.put(channel.confirmed, true);
        var saved = body(save(input).andExpect(status().isOk()));
        assertThat(saved.path(channel.id).asText()).isEqualTo(channel.replacementId());
        assertThat(saved.path(channel.mapping)).isEqualTo(before.path(channel.mapping));
        assertThat(saved.path(channel.other().mapping)).isEqualTo(before.path(channel.other().mapping));
        assertThat(saved.path(channel.other().id)).isEqualTo(before.path(channel.other().id));
        save(input).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void stagedTemplateCannotBeEnabledWithoutReviewAndAcceptsAnIntentionalEmptyMap(Channel channel) throws Exception {
        var before = read();
        var input = request(before).put(channel.id, channel.replacementId());
        if (channel == Channel.VOICE) input.putNull(channel.mapping); // Exercise both omitted and explicit null.
        var staged = body(save(input).andExpect(status().isOk()));
        assertThat(staged.path(channel.id).asText()).isEqualTo(channel.replacementId());
        assertThat(read().path(channel.mapping).isNull()).isTrue();
        assertThat(staged.path(channel.other().mapping)).isEqualTo(before.path(channel.other().mapping));

        var enable = request(staged).put(channel.enabled, true);
        save(enable).andExpect(status().isBadRequest());
        enable.put(channel.confirmed, true).putNull(channel.mapping);
        save(enable).andExpect(status().isBadRequest());
        enable.remove(channel.confirmed);
        map(enable, channel);
        save(enable).andExpect(status().isBadRequest());
        assertThat(read()).isEqualTo(staged);

        enable.putObject(channel.mapping);
        enable.put(channel.confirmed, true);
        var enabled = body(save(enable).andExpect(status().isOk()));
        assertThat(enabled.path(channel.enabled).asBoolean()).isTrue();
        assertThat(read().path(channel.mapping)).isEqualTo(json.createObjectNode());
        assertThat(enabled.path(channel.other().enabled).asBoolean()).isFalse();
    }

    @Test void unrelatedChangePreservesMappingsAndReceiptCredentials() throws Exception {
        var configured = request(read()).put("access_key_id", "fake-provider-id").put("access_key_secret", "fake-provider-secret");
        for (var channel : Channel.values()) map(configured, channel);
        var before = body(save(configured).andExpect(status().isOk()));
        var saved = body(save(request(before).put("sms_sign_name", "Different fixture sign")).andExpect(status().isOk()));
        for (var channel : Channel.values()) {
            assertThat(saved.path(channel.mapping)).isEqualTo(before.path(channel.mapping));
        }
        assertThat(saved.path("access_key_id_masked")).isEqualTo(before.path("access_key_id_masked"));
        assertThat(saved.path("secret_configured").asBoolean()).isTrue();
        assertThat(notificationClient.queryReady()).isTrue();
    }

    private String login(String password) throws Exception {
        return body(mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("username", "admin", "password", password, "audience", "management"))))
            .andExpect(status().isOk())).path("access_token").asText();
    }

    private JsonNode read() throws Exception {
        return body(mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private ResultActions save(ObjectNode input) throws Exception {
        return mvc.perform(put(PATH).header("Authorization", "Bearer " + token)
            .contentType("application/json").content(json.writeValueAsBytes(input)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private ObjectNode request(JsonNode current) {
        var input = json.createObjectNode().put("expected_revision", current.path("revision").asLong())
            .put("sms_enabled", false).put("voice_enabled", false).put("test_mode_enabled", false);
        input.set("sms_sign_name", current.path("sms_sign_name"));
        input.set("sms_template_code", current.path("sms_template_code"));
        input.set("tts_code", current.path("tts_code"));
        return input;
    }

    private void map(ObjectNode input, Channel channel) {
        input.putObject(channel.mapping).put(channel.variable, channel.field);
    }
}
